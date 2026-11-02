"""Emit a ready-to-preview self-contained composition; never edits index.html."""
import json,re,sys
from pathlib import Path
p=Path(__file__).resolve().parent
view=sys.argv[1] if len(sys.argv)>1 else 'home-night'
config={'view':view,'controls':'--clean' not in sys.argv,'notes':'--clean' not in sys.argv}
if '--day' in sys.argv: config['theme']='day'
text=(p/'index.html').read_text()
text=re.sub(r'(<script id="initial-view" type="application/json">).*?(</script>)',lambda m:m[1]+json.dumps(config)+m[2],text,count=1)
print(text)
