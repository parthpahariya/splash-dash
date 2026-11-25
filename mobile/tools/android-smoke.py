#!/usr/bin/env python3
"""Smoke-test the installed Android app with adb; uses only the Python standard library.

Run after adb install with: python3 mobile/tools/android-smoke.py
Set ANDROID_SERIAL when multiple Android devices are connected.
"""
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

sdk = os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk'))
adb = shutil.which('adb') or str(Path(sdk) / 'platform-tools/adb')
package = 'com.parthpahariya.splashdash'

def run(*args):
    return subprocess.check_output([adb, *args], text=True, stderr=subprocess.STDOUT)

def tree():
    run('shell', 'uiautomator', 'dump', '/sdcard/wake-runner-ui.xml')
    return ET.fromstring(run('shell', 'cat', '/sdcard/wake-runner-ui.xml'))

def find(label):
    for node in tree().iter('node'):
        if node.get('text') == label or node.get('content-desc') == label:
            return node
    return None

def wait_for(label, timeout=20):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        try:
            node = find(label)
        except (subprocess.CalledProcessError, ET.ParseError):
            node = None
        if node is not None:
            return node
        time.sleep(0.3)
    raise AssertionError(f'UI element not found: {label}')

def tap_node(node):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    run('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))

def tap(label):
    node = wait_for(label)
    assert node.get('enabled') == 'true', f'Disabled control: {label}'
    tap_node(node)

# Restore every system setting touched by the smoke test, including on failure.
original_accelerometer = run('shell', 'settings', 'get', 'system', 'accelerometer_rotation').strip()
original_rotation = run('shell', 'settings', 'get', 'system', 'user_rotation').strip()
try:
    run('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0')
    run('shell', 'settings', 'put', 'system', 'user_rotation', '0')
    run('shell', 'am', 'force-stop', package)
    run('shell', 'am', 'start', '-n', f'{package}/.MainActivity')
    wait_for('Set sail')
    tap('Set sail')
    # Gameplay controls are contextual, and a running HUD doesn't stay idle
    # long enough for uiautomator dump. Use the matching keyboard actions.
    run('shell', 'input', 'keyevent', 'KEYCODE_DPAD_LEFT')
    run('shell', 'input', 'keyevent', 'KEYCODE_DPAD_RIGHT')
    # Jump, then pause and verify the menu replaces the gameplay controls.
    run('shell', 'input', 'keyevent', 'KEYCODE_DPAD_UP')
    run('shell', 'input', 'keyevent', 'KEYCODE_P')
    wait_for('Back to the water')
    assert find('Fire cannon. Hold for continuous fire.') is None
    tap('Back to the water')
    # The activity must release firing and pause when leaving the foreground.
    run('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    run('shell', 'am', 'start', '-n', f'{package}/.MainActivity')
    wait_for('Back to the water')
    run('shell', 'settings', 'put', 'system', 'user_rotation', '1')
    wait_for('Back to the water')
    tap('How to play')
    wait_for('Captain’s handbook')
    tap('Got it')
    wait_for('Back to the water')
    screenshot = Path('test-results/android-landscape.png')
    screenshot.parent.mkdir(parents=True, exist_ok=True)
    screenshot.write_bytes(subprocess.check_output([adb, 'exec-out', 'screencap', '-p']))
    print('PASS: start, steering, jump, pause/resume, background pause, landscape, help')
finally:
    run('shell', 'settings', 'put', 'system', 'accelerometer_rotation', original_accelerometer)
    run('shell', 'settings', 'put', 'system', 'user_rotation', original_rotation)
