"""Bounded input validation for the fixture, not a replacement for Android policy."""
import json
import re
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from scenarios import preferences, restrictions

MAX_BODY = 262144
MAX_INTEGER = 9007199254740991  # Phone editors require exact JS integers.


class Refusal(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


def require(condition, status=400, message="Invalid synthetic fixture request. Check the fields."):
    if not condition:
        raise Refusal(status, message)


def obj(value, allowed, required=()):
    require(type(value) is dict and set(value) <= set(allowed) and set(required) <= set(value))
    return value


def integer(value, minimum=0, maximum=MAX_INTEGER):
    require(type(value) is int and minimum <= value <= maximum)
    return value


def boolean(value):
    require(type(value) is bool)
    return value


def string(value, maximum=8000, blank=False, multiline=False):
    require(type(value) is str and len(value) <= maximum and (blank or bool(value.strip())))
    require(all((ord(c) >= 32 and not 127 <= ord(c) <= 159 and c not in '\u2028\u2029'
                 and not 0xD800 <= ord(c) <= 0xDFFF) or (multiline and c in '\n\t') for c in value))
    return value


def strings(value, count, pattern=None):
    require(type(value) is list and len(value) <= count)
    for item in value:
        string(item, 255)
        require(pattern is None or re.fullmatch(pattern, item))
    require(len(set(value)) == len(value))
    return value


def decode(data):
    require(len(data) <= MAX_BODY)
    # Bound nesting before calling the recursive JSON decoder, including ignored fields.
    depth, quoted, escaped = 0, False, False
    for byte in data:
        if quoted:
            if escaped:
                escaped = False
            elif byte == 92:
                escaped = True
            elif byte == 34:
                quoted = False
        elif byte == 34:
            quoted = True
        elif byte in (91, 123):
            depth += 1
            require(depth <= 24)
        elif byte in (93, 125):
            depth -= 1
            require(depth >= 0)

    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result)
            result[key] = value
        return result

    def bad_constant(_):
        raise ValueError()

    try:
        value = json.loads(data.decode('utf-8'), object_pairs_hook=pairs, parse_constant=bad_constant)
    except (ValueError, UnicodeError, RecursionError):
        raise Refusal(400, "Invalid JSON fixture request.") from None
    require(type(value) is dict)
    return value


REFERENCE = r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"
PACKAGE = r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+"


def libraries(value):
    require(type(value) is list and len(value) <= 32)
    for item in value:
        obj(item, ('providerId', 'libraryId'), ('providerId', 'libraryId'))
        for text in item.values():
            require(re.fullmatch(REFERENCE, string(text, 128)))
    require(len({(i['providerId'], i['libraryId']) for i in value}) == len(value))


def profile_draft(value):
    obj(value, ('displayName', 'kind', 'preferences', 'restrictions'), ('displayName',))
    name = string(value['displayName'], 60)
    require(name == name.strip())
    kind = value.get('kind', 'adult')
    require(kind in ('adult', 'child'))
    # Actual DTO defaults are empty preferences, unlike the populated scenario.
    prefs = dict(preferences(), preferredMediaProviders=[], audioLanguages=[], subtitleLanguages=[])
    prefs.update(obj(value.get('preferences', {}), prefs))
    strings(prefs['preferredMediaProviders'], 16, REFERENCE)
    libraries(prefs['preferredLibraries'])
    for key in ('audioLanguages', 'subtitleLanguages'):
        values = strings(prefs[key], 8, r'[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*')
        require(len({s.lower() for s in values}) == len(values) and all(len(s) <= 64 for s in values))
    require(prefs['subtitles'] in ('off', 'automatic', 'on'))
    boolean(prefs['autoplayNext'])
    limits = restrictions()
    limits.update(obj(value.get('restrictions', {}), limits))
    for key in ('allowUnrated', 'allowAgentActions', 'allowHomeControl'):
        boolean(limits[key])
    if limits['maxAgeRating'] is not None:
        integer(limits['maxAgeRating'], 0, 21)
    if limits['allowedMediaProviders'] is not None:
        strings(limits['allowedMediaProviders'], 16, REFERENCE)
    if limits['allowedLibraries'] is not None:
        libraries(limits['allowedLibraries'])
    if limits['allowedAppPackages'] is not None:
        strings(limits['allowedAppPackages'], 100, PACKAGE)
    if kind == 'child':
        require(limits['maxAgeRating'] is not None and limits['maxAgeRating'] <= 17 and not limits['allowUnrated'])
        require(all(limits[k] is not None for k in ('allowedMediaProviders', 'allowedLibraries', 'allowedAppPackages')))
    return dict(displayName=name, kind=kind, preferences=prefs, restrictions=limits)


def memory_draft(value):
    obj(value, ('revision', 'kind', 'text', 'tags', 'shareWithAgent'), ('revision', 'kind', 'text'))
    require(value['kind'] in ('preference', 'fact'))
    text = string(value['text'], 2000, multiline=True)
    require(text == text.strip())
    tags = strings(value.get('tags', []), 8, r'[a-z0-9][a-z0-9_-]{0,31}')
    return dict(kind=value['kind'], text=text, tags=tags, shareWithAgent=boolean(value.get('shareWithAgent', True)))


def routine_spec(value, owner):
    obj(value, ('name', 'task', 'schedule', 'enabled', 'missedRunPolicy', 'maxLatenessMillis'), ('name', 'task', 'schedule'))
    string(value['name'], 120)
    string(value['task'], 8000, multiline=True)
    schedule = value['schedule']
    require(type(schedule) is dict)
    kind = schedule.get('type')
    fields = {'once': ('at',), 'interval': ('firstAt', 'everyMillis'),
              'weekly': ('days', 'time', 'zone'), 'monthly': ('day', 'time', 'zone')}
    require(type(kind) is str and kind in fields)
    obj(schedule, ('type', *fields[kind]), ('type', *fields[kind]))
    if kind in ('once', 'interval'):
        integer(schedule['at' if kind == 'once' else 'firstAt'], 0, 7258118400000)
        if kind == 'interval':
            integer(schedule['everyMillis'], 900000, 31622400000)
    else:
        require(re.fullmatch(r'(?:[01][0-9]|2[0-3]):[0-5][0-9]', string(schedule['time'], 5)))
        zone = string(schedule['zone'], 128)
        try:
            ZoneInfo(zone)
        except (ZoneInfoNotFoundError, ValueError):
            raise Refusal(400, "Unknown fixture calendar zone; use an installed IANA zone.") from None
        if kind == 'weekly':
            require(type(schedule['days']) is list and 1 <= len(schedule['days']) <= 7)
            for day in schedule['days']:
                integer(day, 1, 7)
            require(len(set(schedule['days'])) == len(schedule['days']))
        else:
            integer(schedule['day'], 1, 31)
    policy = value.get('missedRunPolicy', 'RUN_LATEST')
    require(policy in ('SKIP', 'RUN_LATEST'))
    return dict(value, enabled=boolean(value.get('enabled', True)), missedRunPolicy=policy,
                maxLatenessMillis=integer(value.get('maxLatenessMillis', 86400000), 60000, 2592000000), ownerProfileId=owner)


def layout(value):
    obj(value, ('version', 'rows'), ('rows',))
    require(type(value.get('version', 1)) is int and value.get('version', 1) == 1)
    rows = value['rows']
    require(type(rows) is list and 1 <= len(rows) <= 20)
    ids = set()
    for row in rows:
        obj(row, ('id', 'title', 'style', 'source'), ('id', 'title', 'source'))
        require(re.fullmatch(r'[a-zA-Z0-9_-]{1,64}', string(row['id'], 64)) and row['id'] not in ids)
        ids.add(row['id'])
        string(row['title'], 120)
        require(row.get('style', 'wide') in ('wide', 'poster', 'app'))
        source = row['source']
        require(type(source) is dict)
        kind = source.get('type')
        keys = dict(apps=('packages',), watchlist=(), jellyfin_resume=(), jellyfin_latest=('libraryName',),
                    jellyfin_search=('query',), plex_library=('libraryId',), plex_recent=('libraryId',),
                    plex_search=('libraryId', 'query'), plex_resume=(), plex_on_deck=(),
                    home_entities=('entityIds',), pinned=('items',))
        require(type(kind) is str and kind in keys)
        obj(source, ('type', *keys[kind]))
        if kind == 'apps' and source.get('packages') is not None:
            strings(source['packages'], 100)
        if kind == 'home_entities':
            strings(source.get('entityIds'), 100)
        if kind == 'jellyfin_latest' and source.get('libraryName') is not None:
            string(source['libraryName'], 500)
        if kind.endswith('search'):
            string(source.get('query'), 500)
        if kind in ('plex_library', 'plex_recent', 'plex_search'):
            require(re.fullmatch(r'[0-9]{1,18}', string(source.get('libraryId'), 18)))
        if kind == 'pinned':
            items = source.get('items')
            require(type(items) is list and len(items) <= 100)
            for item in items:
                obj(item, ('title', 'action', 'subtitle', 'imageUrl'), ('title', 'action'))
                string(item['title'], 200)
                for key in ('subtitle', 'imageUrl'):
                    if item.get(key) is not None:
                        string(item[key], 8192)
                action = item['action']
                require(type(action) is dict)
                fields = dict(launch_app=('packageName',), search_app=('packageName', 'query'),
                              open_uri=('uri',), play_jellyfin=('itemId',), ask_agent=('prompt',),
                              home_service=('domain', 'service', 'entityId'))
                action_type = action.get('type')
                require(type(action_type) is str and action_type in fields)
                obj(action, ('type', *fields[action_type], *(('packageName',) if action_type == 'open_uri' else ())),
                    ('type', *fields[action_type]))
                for field in fields[action_type]:
                    string(action[field], 8192)
    return dict(value, version=1)
