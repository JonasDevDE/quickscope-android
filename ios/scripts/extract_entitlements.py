import os, plistlib, sys

def extract(prov_path, out_path):
    with open(prov_path, 'rb') as f:
        data = f.read()
    s = data.find(b'<?xml')
    e = data.find(b'</plist>') + 8
    pl = plistlib.loads(data[s:e])
    ent = pl['Entitlements']
    with open(out_path, 'wb') as f:
        plistlib.dump(ent, f)
    print(f"Extracted entitlements to {out_path}")

if __name__ == '__main__':
    if len(sys.argv) < 3:
        print("Usage: extract_entitlements.py <provision_path> <out_path>")
        sys.exit(1)
    extract(sys.argv[1], sys.argv[2])
