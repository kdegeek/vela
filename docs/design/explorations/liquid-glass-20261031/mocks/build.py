from pathlib import Path
import base64,re
ROOT=Path(__file__).resolve().parent
text=(ROOT/'prototype.html').read_text()
for path in (ROOT/'assets').iterdir():
    if path.suffix in ['.webp','.woff2']:
        mime='font/woff2' if path.suffix=='.woff2' else 'image/webp'
        text=text.replace('ASSET:'+path.name,'data:'+mime+';base64,'+base64.b64encode(path.read_bytes()).decode())
(ROOT/'index.html').write_text(text)
print('Self-contained export:',len(text.encode()),'bytes')
