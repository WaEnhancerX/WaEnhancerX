#!/usr/bin/env python3
"""Verify the provided APK archives and check a *sample* of DexKit anchors.

Usage: python qa/audit_apks.py /path/to/WAEX.apk /path/to/WhatsApp.apk
Neither presence nor absence of a string proves that a hook is operational:
DEX search semantics, method signatures, device APIs and execution still matter.
"""
import re
import sys
from pathlib import Path
from zipfile import ZipFile

# Literal anchors used by the submitted source code; in the same version some
# can be absent, pointing to an unresolved compatibility risk for those hooks.
ANCHORS = {
    'Read receipts': 'ReadReceipts/sendReceiptForIncomingMessage',
    'Call recording callback': 'VoiceServiceEventCallback',
    'Image compression': 'image/compress quality',
    'Video frame rate': 'video/encoder fps',
    'Message sender JID': 'FMessage/getSenderUserJid/key.id',
    'Typing presence': 'HandleMeComposing/sendComposing',
    'Availability': 'presencestatemanager/startTransitionToUnavailable/new-state',
    'Pinned chats limit': 'pininfo/setpin/failed-already-max-pinned',
    'View-once SQL': 'INSERT_VIEW_ONCE_SQL',
    'Send message event': 'app/sendmessage/message_sent',
    'Voice-call state': 'voip/callStateChangedOnUIThread',
    'Message handler': 'MessageHandler/start',
    'Presence receive': 'app/xmpp/recv/handle_available',
    'Status playback': 'playbackPage/onPlaybackContentFinished',
    'Chat info events': 'chatInfo/incrementUnseenImportantMessageCount',
    'Channel subscription': 'hasNewsletterSubscriptions',
    'Archive preview': 'archive/set-content-indicator-to-empty',
    'Chat bubble drawable': 'balloon_incoming_normal',
}



def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    manager, host = map(Path, sys.argv[1:])
    for p in (manager, host):
        if not p.is_file(): raise SystemExit(f'Not found: {p}')
    with ZipFile(manager) as z:
        assert z.testzip() is None, 'Manager APK fails ZIP CRC'
        assert 'AndroidManifest.xml' in z.namelist(), 'Manager manifest missing'
        print(f'Manager: {manager.name}; native ABIs={sorted({n.split("/")[1] for n in z.namelist() if n.startswith("lib/")})}; CRC OK')
    with ZipFile(host) as z:
        names = z.namelist()
        assert z.testzip() is None, 'Host APK fails ZIP CRC'
        dex_names = [n for n in names if re.fullmatch(r'classes\d*\.dex', n)]
        assert dex_names, 'Host has no classes*.dex'
        xml = z.read('AndroidManifest.xml')
        print(f'WhatsApp: {host.name}; {len(dex_names)} DEX, {len(names)} ZIP entries; '
              f'ABIs={sorted({n.split("/")[1] for n in names if n.startswith("lib/") and n.endswith(".so")})}; CRC OK')
        print('Version 2.26.39.79 in binary manifest:', '2.26.39.79'.encode('utf-16le') in xml)
        present = set()
        for dex in dex_names:
            content = z.read(dex)
            for label, anchor in ANCHORS.items():
                if anchor.encode('utf-8') in content:
                    present.add(label)
        print(f'DEX literal anchors found: {len(present)}/{len(ANCHORS)} (NOT a functional test)')
        for label in ANCHORS:
            print(f'  {"PRESENT" if label in present else "ABSENT "} {label}: {ANCHORS[label]}')

if __name__ == '__main__':
    main()
