"""Create updater metadata from the actual Gradle-built APK; used by GitHub Actions."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil

parser = argparse.ArgumentParser()
parser.add_argument('--tag', required=True)
parser.add_argument('--repo', default='MichaelCommitsAt3AM/home_wifi_manager')
parser.add_argument('--output', default='dist')
args = parser.parse_args()
if not re.fullmatch(r'v\d+\.\d+\.\d+', args.tag):
    raise SystemExit('Release tag must be vMAJOR.MINOR.PATCH')
metadata_path = Path('android/app/build/outputs/apk/release/output-metadata.json')
metadata = json.loads(metadata_path.read_text(encoding='utf-8'))
if len(metadata['elements']) != 1:
    raise SystemExit('Expected one universal Android APK')
element = metadata['elements'][0]
if element['versionName'] != args.tag[1:] or metadata['applicationId'] != 'com.homewifi.manager':
    raise SystemExit('Tag/version/application mismatch')
apk = metadata_path.parent / element['outputFile']
output = Path(args.output)
output.mkdir(parents=True, exist_ok=True)
name = f'HomeWiFi-{args.tag}.apk'
shutil.copyfile(apk, output/name)
digest = hashlib.sha256(apk.read_bytes()).hexdigest()
manifest = dict(versionCode=element['versionCode'], versionName=element['versionName'],
                apkUrl=f'https://github.com/{args.repo}/releases/download/{args.tag}/{name}',
                sha256=digest, size=apk.stat().st_size, minSdk=26)
(output/'update.json').write_text(json.dumps(manifest, indent=2)+'\n', encoding='utf-8')
(output/'SHA256SUMS').write_text(f'{digest}  {name}\n', encoding='utf-8')
print(f'Prepared Android {manifest["versionName"]} ({manifest["versionCode"]}), {manifest["size"]} bytes')
