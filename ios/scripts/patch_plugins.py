import glob, os

cache_dir = os.path.expanduser("~/.pub-cache")

# 1. Patch camera notifications
for f in glob.glob(f"{cache_dir}/**/DefaultCamera.swift", recursive=True):
    with open(f, "r") as fp:
        content = fp.read()
    if "AVCaptureSession.wasInterruptedNotification" in content or "AVCaptureSession.runtimeErrorNotification" in content:
        content = content.replace("AVCaptureSession.wasInterruptedNotification", "NSNotification.Name(\"AVCaptureSessionWasInterruptedNotification\")")
        content = content.replace("AVCaptureSession.runtimeErrorNotification", "NSNotification.Name(\"AVCaptureSessionRuntimeErrorNotification\")")
        with open(f, "w") as fp:
            fp.write(content)
        print(f"Patched {f}")

# 2. Patch device_info_plus visionOS
for f in glob.glob(f"{cache_dir}/**/FPPDeviceInfoPlusPlugin.m", recursive=True):
    with open(f, "r") as fp:
        content = fp.read()
    if "[info isiOSAppOnVision]" in content:
        content = content.replace("[info isiOSAppOnVision]", "NO")
        with open(f, "w") as fp:
            fp.write(content)
        print(f"Patched {f}")
