"""Serialized, RAM-only route responses for the shipped companion page.

No background scheduler, agent, broker, external listener, or device operations.
"""
from copy import deepcopy
import re
import threading
from urllib.parse import parse_qs, urlsplit
from uuid import uuid4

from scenarios import EXTERNAL_TOKEN, NOW, SCENARIOS, ident, seed
from validation import boolean, integer, layout, memory_draft, obj, profile_draft, require, routine_spec, string


class Store:
    def __init__(self, scenario='idle'):
        self.lock = threading.Lock()
        self.instance = uuid4().hex
        self.generation = 0
        self.reset(scenario)

    def reset(self, scenario):
        require(scenario in SCENARIOS)
        prior_revision = getattr(self, 'revision', 0)
        prior_routine_revision = max((r['revision'] for r in getattr(self, 'routines', [])), default=0)
        self.generation += 1
        self.scenario, self.serial = scenario, 0
        self.state, self.profiles, self.memories, self.routines, self.history, self.layout = seed(scenario, self.generation)
        self.active = self.profiles[0]['id']
        self.revision = max(prior_revision + 1, self.generation * 10000 + 7)
        for routine in self.routines:
            routine['revision'] = max(routine['revision'], prior_routine_revision + 1)
        if self.state['pendingId']:
            self.state['pendingId'] = f'synthetic-phone-{self.instance}-{self.generation}'
        self.external_enabled = False
        self.poll_fault = None
        self.bindings = {r['id']: self.binding() for r in self.routines}

    def new_id(self, kind):
        self.serial += 1
        return ident(f'{self.generation}-{kind}-{self.serial}')

    def metadata(self):
        return dict(synthetic=True, scenario=self.scenario, generation=self.generation,
                    scenarios=list(SCENARIOS), revision=self.revision, pollFault=self.poll_fault,
                    pendingId=self.state['pendingId'], persistent=False, devicesConnected=False)

    def idle(self):
        require(not self.state['running'], 409, 'Another controller is active. Stop it or wait. [Synthetic fixture]')

    def expected(self, body, actual=None):
        require(integer(body.get('revision')) == (self.revision if actual is None else actual), 409,
                'This item changed or has pending work. Reload before trying again. [Synthetic fixture]')

    @staticmethod
    def find(items, item_id):
        item = next((item for item in items if item['id'] == item_id), None)
        require(item is not None, 404, 'The requested synthetic item was not found.')
        return item

    def profiles_response(self):
        return dict(revision=self.revision, activeProfileId=self.active, profiles=self.profiles)

    def commit(self, value):
        self.revision += 1
        self.state['lines'] = []  # Owner changes invalidate conversation context.
        return dict(revision=self.revision, value=value)

    def external(self):
        return dict(enabled=self.external_enabled, address='127.0.0.1:8766',
                    credentialPresent=self.external_enabled, error=None)

    def stop(self, final='canceled'):
        for task in self.state['tasks']:
            if task['state'] == 'running':
                task.update(state=final, finishedAt=NOW + 3000,
                            detail='Synthetic task ended; no external effect was attempted.')
        self.state.update(running=False, pending=None, pendingId=None)

    def binding(self):
        p = self.find(self.profiles, self.active)
        return deepcopy((p['id'], p['kind'], p['preferences'], p['restrictions']))

    def cancel_runs(self, routine_id):
        for run in self.history:
            if run['routineId'] == routine_id and run['state'] in ('QUEUED', 'RUNNING', 'CANCELING'):
                run.update(state='CANCELED', finishedAt=NOW + 3000, detail='Synthetic cancellation; nothing executed.')

    def enqueue(self, routine, key, restarted_from=None):
        string(key, 128)
        require(re.fullmatch(r'[A-Za-z0-9_-]{1,128}', key))
        existing = next((r for r in self.history if r['routineId'] == routine['id'] and r['requestKey'] == key), None)
        if existing:
            require(existing['restartedFrom'] == restarted_from and
                    existing['source'] == ('RESTART' if restarted_from else 'MANUAL'), 400,
                    'Synthetic request key already belongs to another operation.')
            return existing
        require(len(self.history) < 100, 409, 'Synthetic run history full. Reset the fixture.')
        if restarted_from is None:
            require(not any(r['routineId'] == routine['id'] and r['state'] in ('QUEUED', 'RUNNING', 'CANCELING') for r in self.history), 409,
                    'This synthetic routine already has pending work.')
        else:
            self.cancel_runs(routine['id'])
        run = dict(id=self.new_id('run'), routineId=routine['id'], revision=routine['revision'],
                   name=routine['spec']['name'], task=routine['spec']['task'],
                   source='RESTART' if restarted_from else 'MANUAL', scheduledAt=NOW, queuedAt=NOW,
                   expiresAt=NOW + routine['spec']['maxLatenessMillis'], state='QUEUED', startedAt=None,
                   finishedAt=None, detail='Synthetic queue only; no scheduler or runner exists.',
                   requestKey=key, restartedFrom=restarted_from, ownerProfileId=routine['spec']['ownerProfileId'])
        # Queue admission is separate from dispatch. Reflect a stale binding as a failed
        # synthetic run, never silently reassign it to the currently active profile.
        if self.bindings.get(routine['id']) != self.binding():
            run.update(state='FAILED', finishedAt=NOW,
                       detail='Synthetic profile/policy changed. Edit from the intended profile before running.')
        self.history.append(run)
        return run

    def dispatch(self, method, target, body):
        # Single lock covers revision checking AND publication, including reset/answer.
        with self.lock:
            return deepcopy(self.route(method, target, body))

    def route(self, method, target, body):
        url = urlsplit(target)
        path = url.path
        query = parse_qs(url.query, keep_blank_values=True, max_num_fields=8)
        require(not query or re.fullmatch(r'/api/profiles/[a-z0-9-]+/memories', path))
        if path.startswith('/__fixture/'):
            if method == 'GET' and path == '/__fixture/state':
                return self.metadata()
            require(method == 'POST', 405, 'Fixture control requires POST.')
            if path == '/__fixture/scenario':
                obj(body, ('scenario',), ('scenario',))
                self.reset(string(body['scenario'], 20))
            elif path == '/__fixture/reset':
                obj(body, ())
                self.reset(self.scenario)
            elif path == '/__fixture/conflict':
                obj(body, ('target', 'id'), ('target',))
                if body['target'] == 'household':
                    require('id' not in body)
                    self.revision += 1
                else:
                    require(body['target'] == 'routine')
                    self.find(self.routines, body.get('id'))['revision'] += 1
            elif path == '/__fixture/poll-fault':
                obj(body, ('status',), ('status',))
                require(body['status'] is None or type(body['status']) is int and body['status'] in (401, 503))
                self.poll_fault = body['status']
            else:
                require(False, 404, 'Unknown fixture control.')
            return self.metadata()

        if path == '/api/state' and method == 'GET':
            if self.poll_fault:
                require(False, self.poll_fault, 'Synthetic polling failure; clear /__fixture/poll-fault to reconnect.')
            return self.state
        if path == '/api/answer' and method == 'POST':
            obj(body, ('allow', 'promptId'), ('allow', 'promptId'))
            allow = boolean(body['allow'])
            prompt = string(body['promptId'], 100)
            require(prompt == self.state['pendingId'], 409, 'That prompt has expired. Refresh before answering.')
            self.stop('complete' if allow else 'canceled')
            self.state['lines'].append(dict(kind='said', text='Synthetic approval ' + ('allowed' if allow else 'declined') + '; RAM only, no home/device effect.'))
            return {}
        if path in ('/api/stop', '/api/history/clear') and method == 'POST':
            obj(body, ())
            self.stop()
            if path.endswith('/clear'):
                self.state.update(lines=[], tasks=[])
            return {}
        if path == '/api/ask' and method == 'POST':
            obj(body, ('text',), ('text',))
            task = string(body['text'], 8000, multiline=True).strip()
            self.stop()
            task_id = self.new_id('task')
            self.state.update(running=True, lines=[dict(kind='you', text=task),
                              dict(kind='step', text='Synthetic work waiting. Use Stop; no provider is running.')])
            self.state['tasks'] = (self.state['tasks'] + [dict(id=task_id, task=task, state='running', startedAt=NOW,
                                                            finishedAt=None, detail='Synthetic work waiting for Stop.')])[-50:]
            return dict(taskId=task_id)
        if path == '/api/remote' and method == 'POST':
            obj(body, ('key',), ('key',))
            require(body['key'] in ('up', 'down', 'left', 'right', 'select', 'back', 'home'))
            self.idle()
            return {}
        if path == '/api/connections/test' and method == 'POST':
            obj(body, ())
            return {name: ('Synthetic connection failure. No connection attempted.' if self.scenario == 'error'
                           else 'Synthetic fixture response. No connection attempted.')
                    for name in ('AI', 'Jellyfin', 'Plex', 'Home Assistant')}
        if path == '/api/settings' and method == 'POST':
            self.idle()
            current = self.state['settings']
            obj(body, current)
            updated = dict(current)
            for key, value in body.items():
                if type(current[key]) is bool:
                    boolean(value)
                else:
                    string(value, 8192, blank=True)
                if key in ('apiKey', 'jellyfinToken', 'homeAssistantToken', 'plexToken'):
                    require(value in ('', '••••••') or value.startswith('PUBLIC-'), 400,
                            'Fixture settings accept only empty, masked, or PUBLIC- synthetic credentials.')
                    value = '••••••' if value else ''
                updated[key] = value
            require(updated['provider'] in ('openai', 'anthropic') and updated['appearance'] in ('day', 'night'))
            require(len(updated['model']) <= 200)
            self.state['settings'] = updated
            self.state['lines'] = []
            return {}
        if path == '/api/layout':
            if method == 'GET':
                return self.layout
            if method == 'PUT':
                self.idle()
                self.layout = layout(body)
                return {}
        if path == '/api/apps' and method == 'GET':
            return dict(apps=[dict(packageName='synthetic.fixture.player', label='Synthetic player'),
                              dict(packageName='synthetic.fixture.library', label='Synthetic library')])
        if path == '/api/household/reload' and method == 'POST':
            obj(body, ())
            self.stop()
            self.state['lines'] = []
            return self.profiles_response()
        if path == '/api/profiles':
            if method == 'GET':
                return self.profiles_response()
            if method == 'POST':
                obj(body, ('revision', 'profile'), ('revision', 'profile'))
                self.idle()
                self.expected(body)
                draft = profile_draft(body['profile'])
                require(len(self.profiles) < 8, 400, 'Synthetic household allows at most eight profiles.')
                saved = dict(draft, id=self.new_id('profile'), createdAt=NOW, updatedAt=NOW)
                self.profiles.append(saved)
                return self.commit(saved)
        if path == '/api/profiles/active' and method == 'POST':
            obj(body, ('revision', 'profileId'), ('revision', 'profileId'))
            self.expected(body)
            saved = self.find(self.profiles, body['profileId'])
            self.stop()
            self.active = saved['id']
            return self.commit(saved)
        match = re.fullmatch(r'/api/profiles/([a-z0-9-]+)(?:/(export|memories)(?:/([a-z0-9-]+))?)?', path)
        if match:
            profile_id, surface, memory_id = match.groups()
            profile = self.find(self.profiles, profile_id)
            entries = [m for m in self.memories if m['profileId'] == profile_id]
            if method == 'GET' and surface == 'export' and memory_id is None:
                return dict(exportVersion=1, householdRevision=self.revision, profile=profile, memories=entries)
            if method == 'GET' and surface == 'memories' and memory_id is None:
                require(set(query) <= {'offset', 'limit', 'expectedRevision'} and all(len(v) == 1 for v in query.values()))
                nums = {}
                for key, values in query.items():
                    require(re.fullmatch(r'0|[1-9][0-9]{0,15}', values[0]))
                    nums[key] = integer(int(values[0]))
                offset = integer(nums.get('offset', 0), 0, 2147483647)
                limit = integer(nums.get('limit', 50), 1, 100)
                if 'expectedRevision' in nums:
                    self.expected(dict(revision=nums['expectedRevision']))
                entries.sort(key=lambda m: (-m['updatedAt'], m['id']))
                return dict(profileId=profile_id, revision=self.revision, total=len(entries), entries=entries[offset:offset + limit])
            require(method in ('POST', 'PUT', 'DELETE'), 405, 'Unsupported synthetic profile method.')
            self.idle()
            self.expected(body)
            if surface is None and method == 'PUT':
                obj(body, ('revision', 'profile'), ('revision', 'profile'))
                profile.update(profile_draft(body['profile']), updatedAt=NOW + self.revision)
                return self.commit(profile)
            if surface is None and method == 'DELETE':
                obj(body, ('revision', 'deleteOwnedMemories'), ('revision', 'deleteOwnedMemories'))
                confirmed = boolean(body['deleteOwnedMemories'])
                require(profile_id != self.active and len(self.profiles) > 1, 409, 'Cannot delete the active or last synthetic profile.')
                require(not entries or confirmed, 409, 'Synthetic profile has memory; explicit deletion confirmation required.')
                self.profiles.remove(profile)
                self.memories = [m for m in self.memories if m['profileId'] != profile_id]
                return self.commit(profile_id)
            if surface == 'memories':
                if memory_id == 'clear' and method == 'POST':
                    obj(body, ('revision', 'ownerConfirmed'), ('revision', 'ownerConfirmed'))
                    require(boolean(body['ownerConfirmed']))
                    self.memories = [m for m in self.memories if m['profileId'] != profile_id]
                    return self.commit(len(entries))
                if (memory_id is None and method == 'POST') or (memory_id is not None and method == 'PUT'):
                    draft = memory_draft(body)
                    old = self.find(entries, memory_id) if memory_id else None
                    require(old is not None or len(entries) < 100)
                    require(sum(len(m['text']) for m in entries if m is not old) + len(draft['text']) <= 100000)
                    if old is None:
                        old = dict(id=self.new_id('memory'), profileId=profile_id, createdAt=NOW)
                        self.memories.append(old)
                    old.update(draft, source='owner_phone', updatedAt=NOW + self.revision)
                    return self.commit(old)
                if memory_id and method == 'DELETE':
                    obj(body, ('revision',), ('revision',))
                    old = self.find(entries, memory_id)
                    self.memories.remove(old)
                    return self.commit(memory_id)
        if path == '/api/routines':
            if method == 'GET':
                return dict(version=1, routines=self.routines, history=self.history)
            if method == 'POST':
                obj(body, ('spec',), ('spec',))
                spec = routine_spec(body['spec'], self.active)
                require(len(self.routines) < 32, 409, 'Synthetic routine limit reached. Reset the fixture.')
                saved = dict(id=self.new_id('routine'), revision=1, spec=spec, createdAt=NOW, updatedAt=NOW, nextRunAt=None)
                self.routines.append(saved)
                self.bindings[saved['id']] = self.binding()
                return saved
        match = re.fullmatch(r'/api/routines/([a-z0-9-]+)(?:/(enabled|run))?', path)
        if match:
            item_id, action = match.groups()
            routine = self.find(self.routines, item_id)
            if action == 'run' and method == 'POST':
                obj(body, ('requestKey',), ('requestKey',))
                return self.enqueue(routine, body['requestKey'])
            self.expected(body, routine['revision'])
            if action is None and method == 'PUT':
                obj(body, ('revision', 'spec'), ('revision', 'spec'))
                spec = routine_spec(body['spec'], self.active)
                self.cancel_runs(item_id)
                routine.update(spec=spec, revision=routine['revision'] + 1, updatedAt=NOW + self.revision, nextRunAt=None)
                self.bindings[item_id] = self.binding()
                return routine
            if action is None and method == 'DELETE':
                obj(body, ('revision',), ('revision',))
                self.cancel_runs(item_id)
                self.routines.remove(routine)
                self.bindings.pop(item_id, None)
                return {}
            if action == 'enabled' and method == 'POST':
                obj(body, ('revision', 'enabled'), ('revision', 'enabled'))
                enabled = boolean(body['enabled'])
                if routine['spec']['enabled'] == enabled:
                    return routine
                routine['spec']['enabled'] = enabled
                routine['revision'] += 1
                if not enabled:
                    self.cancel_runs(item_id)
                return routine
        match = re.fullmatch(r'/api/routine-runs/([a-z0-9-]+)/(cancel|restart)', path)
        if match and method == 'POST':
            run_id, action = match.groups()
            run = self.find(self.history, run_id)
            if action == 'cancel':
                obj(body, ())
                if run['state'] in ('QUEUED', 'RUNNING', 'CANCELING'):
                    run.update(state='CANCELED', finishedAt=NOW + 3000, detail='Synthetic cancellation completed.')
                return run
            obj(body, ('requestKey',), ('requestKey',))
            return self.enqueue(self.find(self.routines, run['routineId']), body['requestKey'], run_id)
        if path == '/api/external-control' and method == 'GET':
            return self.external()
        if path == '/api/external-control/enable' and method == 'POST':
            obj(body, ('ownerConfirmed',), ('ownerConfirmed',))
            require(boolean(body['ownerConfirmed']))
            self.idle()
            require(not self.external_enabled, 409, 'Revoke the synthetic credential before enabling again.')
            self.external_enabled = True
            return dict(status=self.external(), token=EXTERNAL_TOKEN)
        if path == '/api/external-control/revoke' and method == 'POST':
            obj(body, ())
            self.external_enabled = False
            return self.external()
        require(False, 404, 'Unknown synthetic route or method.')
