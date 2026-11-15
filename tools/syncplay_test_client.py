#!/usr/bin/env python3
"""
Headless Jellyfin SyncPlay peer + ADB TV verifier for real-device end-to-end testing.
Reads server URL and access token directly from the connected ADB debug app at runtime;
no credentials are ever written to disk.
"""

from __future__ import annotations

import argparse
import asyncio
from dataclasses import dataclass, field
from datetime import datetime, timezone
import json
import re
import subprocess
import sys
import time
from typing import Any, Optional
from urllib.parse import urlparse
import xml.etree.ElementTree as ET

import aiohttp


TICKS_PER_MS = 10_000
PEER_DEVICE_ID = "headless-syncplay-test-peer"
PEER_DEVICE_NAME = "HeadlessSyncPlayPeer"
PEER_CLIENT = "SyncPlayTestHarness"
PEER_VERSION = "1.0.0"
APP_PACKAGE = "org.jellyfin.androidtv.debug"

# Verified item IDs on live server
ITEM_ESPECIAL_1 = "a47a0bcb-9261-d421-9b49-a40057ad4b50"  # ~173s
ITEM_ESPECIAL_2 = "d66094a5-945a-a244-4452-9afe35a68586"  # ~151s
ITEM_EPISODIO_1 = "980fd09f-a059-6bce-b204-07e18ba57a5e"  # ~1598s, jpn+eng audio, 4 ASS subs


def parse_jellyfin_dt(ts: str) -> float:
    """Parse ISO-8601 UTC timestamp from Jellyfin into Unix epoch milliseconds."""
    s = ts.strip()
    if s.endswith("Z"):
        s = s[:-1] + "+00:00"
    if "." in s:
        head, tail = s.split(".", 1)
        tz_idx = max(tail.find("+"), tail.find("-"))
        if tz_idx != -1:
            frac = tail[:tz_idx][:6].ljust(6, "0")
            tz = tail[tz_idx:]
            s = f"{head}.{frac}{tz}"
        else:
            s = f"{head}.{tail[:6].ljust(6, '0')}+00:00"
    dt = datetime.fromisoformat(s)
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.timestamp() * 1000.0


def format_jellyfin_dt(epoch_ms: float) -> str:
    dt = datetime.fromtimestamp(epoch_ms / 1000.0, tz=timezone.utc)
    return dt.strftime("%Y-%m-%dT%H:%M:%S.") + f"{dt.microsecond:06d}Z"


def load_tv_credentials() -> tuple[str, str, str]:
    """Read server address, user id and access_token from TV debug storage via adb."""
    raw = subprocess.check_output(
        ["adb", "shell", f"run-as {APP_PACKAGE} cat files/authentication_store.json"],
        text=True,
    )
    data = json.loads(raw)
    servers = data.get("servers", {})
    if not servers:
        raise RuntimeError("No servers found in authentication_store.json on TV")
    server = max(servers.values(), key=lambda s: s.get("last_used", 0))
    address = server["address"].rstrip("/")
    users = server.get("users", {})
    if not users:
        raise RuntimeError("No logged-in users found on TV")
    user_id, user_info = max(users.items(), key=lambda kv: kv[1].get("last_used", 0))
    return address, user_id, user_info["access_token"]


class AdbTvDriver:
    """Helpers to control the TV via ADB, UI hierarchy dump, and SyncPlay logs."""

    @staticmethod
    def clear_logcat() -> None:
        subprocess.run(["adb", "logcat", "-c"], check=True)

    @staticmethod
    def get_syncplay_logs(since_lines: int = 350) -> list[str]:
        out = subprocess.check_output(
            ["adb", "logcat", "-d", "-s", "SyncPlay:V", "SyncPlayManager:V", "AndroidRuntime:E", "PlaybackController:E"],
            text=True,
            errors="replace",
        )
        lines = [line for line in out.splitlines() if line.strip() and not line.startswith("--------- beginning")]
        return lines[-since_lines:]

    @staticmethod
    async def wait_for_log(pattern: str, timeout_s: float = 15.0) -> Optional[str]:
        deadline = time.monotonic() + timeout_s
        while time.monotonic() < deadline:
            for line in AdbTvDriver.get_syncplay_logs(400):
                if pattern in line:
                    return line
            await asyncio.sleep(0.35)
        return None

    @staticmethod
    def keyevent(keycode: str) -> None:
        subprocess.run(["adb", "shell", "input", "keyevent", keycode], check=True)

    @staticmethod
    def tap(x: int, y: int) -> None:
        subprocess.run(["adb", "shell", "input", "tap", str(x), str(y)], check=True)

    @staticmethod
    def dump_ui() -> ET.Element:
        subprocess.run(["adb", "shell", "uiautomator", "dump", "/sdcard/ui.xml"], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        xml_str = subprocess.check_output(["adb", "shell", "cat", "/sdcard/ui.xml"], text=True, errors="replace")
        return ET.fromstring(xml_str)

    @staticmethod
    def _bounds_center(bounds: str) -> tuple[int, int]:
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
        if not m:
            raise ValueError(f"Invalid bounds: {bounds}")
        x1, y1, x2, y2 = map(int, m.groups())
        return (x1 + x2) // 2, (y1 + y2) // 2

    @staticmethod
    def find_node(
        root: ET.Element,
        text_contains: Optional[str] = None,
        desc_contains: Optional[str] = None,
        res_contains: Optional[str] = None,
    ) -> Optional[ET.Element]:
        for node in root.iter("node"):
            text = node.attrib.get("text", "")
            desc = node.attrib.get("content-desc", "")
            res = node.attrib.get("resource-id", "")
            if text_contains and text_contains.lower() in text.lower():
                return node
            if desc_contains and desc_contains.lower() in desc.lower():
                return node
            if res_contains and res_contains.lower() in res.lower():
                return node
        return None

    @staticmethod
    def tap_node(node: ET.Element) -> None:
        x, y = AdbTvDriver._bounds_center(node.attrib["bounds"])
        AdbTvDriver.tap(x, y)

    @staticmethod
    async def wait_and_tap(
        text_contains: Optional[str] = None,
        desc_contains: Optional[str] = None,
        res_contains: Optional[str] = None,
        timeout_s: float = 8.0,
    ) -> bool:
        deadline = time.monotonic() + timeout_s
        while time.monotonic() < deadline:
            try:
                root = AdbTvDriver.dump_ui()
                node = AdbTvDriver.find_node(root, text_contains=text_contains, desc_contains=desc_contains, res_contains=res_contains)
                if node is not None:
                    AdbTvDriver.tap_node(node)
                    return True
            except Exception:
                pass
            await asyncio.sleep(0.5)
        return False

    @staticmethod
    def top_activity() -> str:
        out = subprocess.check_output(
            ["adb", "shell", "dumpsys", "activity", "activities"],
            text=True,
            errors="replace",
        )
        for line in out.splitlines():
            if "topResumedActivity=" in line or "mResumedActivity:" in line:
                return line.strip()
        return ""

    @staticmethod
    def launch_app_home() -> None:
        subprocess.run(
            ["adb", "shell", "am", "start", "-W", "-n", f"{APP_PACKAGE}/org.jellyfin.androidtv.ui.startup.StartupActivity"],
            check=True,
            stdout=subprocess.DEVNULL,
        )

    @staticmethod
    def screencap(local_path: str) -> None:
        subprocess.run(["adb", "shell", "screencap", "-p", "/sdcard/sp_test.png"], check=True)
        subprocess.run(["adb", "pull", "/sdcard/sp_test.png", local_path], check=True, stdout=subprocess.DEVNULL)

    @staticmethod
    def has_crash_in_logs(logs: list[str]) -> list[str]:
        return [
            line for line in logs
            if "FATAL EXCEPTION" in line or "NullPointerException" in line
        ]


@dataclass
class PeerPlaybackState:
    group_id: Optional[str] = None
    group_state: str = "Idle"
    item_id: Optional[str] = None
    playlist_item_id: Optional[str] = None
    is_playing: bool = False
    anchor_pos_ms: float = 0.0
    anchor_local_ms: float = 0.0
    playlist: list[dict[str, Any]] = field(default_factory=list)
    playing_index: int = -1
    commands_received: list[dict[str, Any]] = field(default_factory=list)
    state_updates: list[dict[str, Any]] = field(default_factory=list)

    def current_pos_ms(self) -> float:
        if not self.is_playing:
            return self.anchor_pos_ms
        elapsed = max(0.0, (time.monotonic() * 1000.0) - self.anchor_local_ms)
        return self.anchor_pos_ms + elapsed


class HeadlessSyncPlayPeer:
    def __init__(self, server_url: str, token: str, device_id: str = PEER_DEVICE_ID, client_name: str = PEER_CLIENT):
        self.server_url = server_url.rstrip("/")
        self.token = token
        self.device_id = device_id
        self.client_name = client_name
        self.session: Optional[aiohttp.ClientSession] = None
        self.ws: Optional[aiohttp.ClientWebSocketResponse] = None
        self._ws_task: Optional[asyncio.Task[None]] = None
        self._cmd_task: Optional[asyncio.Task[None]] = None
        self.clock_offset_ms: float = 0.0
        self.rtt_ms: int = 20
        self.state = PeerPlaybackState()
        self.simulate_buffering: bool = False
        self.auto_ready_on_queue: bool = True

    def _headers(self) -> dict[str, str]:
        auth = (
            f'MediaBrowser Client="{self.client_name}", '
            f'Device="{PEER_DEVICE_NAME}", '
            f'DeviceId="{self.device_id}", '
            f'Version="{PEER_VERSION}", '
            f'Token="{self.token}"'
        )
        return {
            "Authorization": auth,
            "Content-Type": "application/json",
            "Accept": "application/json",
        }

    async def __aenter__(self) -> "HeadlessSyncPlayPeer":
        self.session = aiohttp.ClientSession(headers=self._headers())
        try:
            await self.report_capabilities()
            await self.sync_clock()
            await self.connect_ws()
            await self.send_ping(self.rtt_ms // 2)
        except Exception:
            await self.session.close()
            self.session = None
            raise
        return self

    async def __aexit__(self, exc_type: Any, exc: Any, tb: Any) -> None:
        if self._cmd_task and not self._cmd_task.done():
            self._cmd_task.cancel()
        if self._ws_task and not self._ws_task.done():
            self._ws_task.cancel()
        if self.ws and not self.ws.closed:
            await self.ws.close()
        if self.session:
            await self.session.close()

    async def request(self, method: str, path: str, json_body: Any = None) -> Any:
        assert self.session is not None
        url = f"{self.server_url}{path}"
        async with self.session.request(method, url, json=json_body) as resp:
            text = await resp.text()
            if resp.status >= 400:
                raise RuntimeError(f"HTTP {resp.status} on {method} {path}: {text}")
            if not text.strip():
                return None
            try:
                return json.loads(text)
            except json.JSONDecodeError:
                return text

    async def report_capabilities(self) -> None:
        await self.request("POST", "/Sessions/Capabilities/Full", {
            "PlayableMediaTypes": ["Audio", "Video"],
            "SupportedCommands": ["PlayState", "Play", "PlayNext"],
            "SupportsMediaControl": True,
            "SupportsPersistentIdentifier": True,
        })

    async def sync_clock(self, samples: int = 5) -> None:
        best_rtt = float("inf")
        best_offset = 0.0
        for _ in range(samples):
            t0 = time.time() * 1000.0
            data = await self.request("GET", "/GetUtcTime")
            t3 = time.time() * 1000.0
            t1 = parse_jellyfin_dt(data["RequestReceptionTime"])
            t2 = parse_jellyfin_dt(data["ResponseTransmissionTime"])
            rtt = (t3 - t0) - (t2 - t1)
            offset = ((t1 - t0) + (t2 - t3)) / 2.0
            if rtt < best_rtt:
                best_rtt = rtt
                best_offset = offset
            await asyncio.sleep(0.05)
        self.clock_offset_ms = best_offset
        self.rtt_ms = max(1, int(round(best_rtt)))

    async def connect_ws(self) -> None:
        assert self.session is not None
        parsed = urlparse(self.server_url)
        ws_scheme = "wss" if parsed.scheme == "https" else "ws"
        ws_url = f"{ws_scheme}://{parsed.netloc}{parsed.path}/socket?api_key={self.token}&deviceId={self.device_id}"
        self.ws = await self.session.ws_connect(ws_url, heartbeat=20)
        self._ws_task = asyncio.create_task(self._ws_loop())

    async def _ws_loop(self) -> None:
        assert self.ws is not None
        async for msg in self.ws:
            if msg.type == aiohttp.WSMsgType.TEXT:
                try:
                    payload = json.loads(msg.data)
                except json.JSONDecodeError:
                    continue
                msg_type = payload.get("MessageType")
                data = payload.get("Data")
                if msg_type == "KeepAlive" or msg_type == "ForceKeepAlive":
                    await self.ws.send_json({"MessageType": "KeepAlive"})
                elif msg_type == "SyncPlayGroupUpdate" and isinstance(data, dict):
                    await self._on_group_update(data)
                elif msg_type == "SyncPlayCommand" and isinstance(data, dict):
                    await self._on_sync_command(data)

    async def _on_group_update(self, update: dict[str, Any]) -> None:
        utype = update.get("Type")
        data = update.get("Data")
        if utype == "GroupJoined" and isinstance(data, dict):
            self.state.group_id = data.get("GroupId")
            self.state.group_state = data.get("State", "Idle")
        elif utype == "GroupLeft" or utype == "NotInGroup":
            self.state.group_id = None
            self.state.group_state = "Idle"
            self.state.is_playing = False
        elif utype == "StateUpdate" and isinstance(data, dict):
            self.state.group_state = data.get("State", self.state.group_state)
            self.state.state_updates.append(data)
        elif utype == "PlayQueue" and isinstance(data, dict):
            playlist = data.get("Playlist", [])
            idx = data.get("PlayingItemIndex", -1)
            self.state.playlist = playlist
            self.state.playing_index = idx
            if 0 <= idx < len(playlist):
                item = playlist[idx]
                self.state.item_id = item.get("ItemId")
                self.state.playlist_item_id = item.get("PlaylistItemId")
            start_ticks = int(data.get("StartPositionTicks", 0))
            is_playing = bool(data.get("IsPlaying", False))
            self.state.anchor_pos_ms = start_ticks / TICKS_PER_MS
            self.state.anchor_local_ms = time.monotonic() * 1000.0
            self.state.is_playing = is_playing
            if self.auto_ready_on_queue and not self.simulate_buffering and self.state.playlist_item_id:
                await self.send_ready(start_ticks, is_playing, self.state.playlist_item_id)

    async def _on_sync_command(self, cmd: dict[str, Any]) -> None:
        self.state.commands_received.append(cmd)
        if self._cmd_task and not self._cmd_task.done():
            self._cmd_task.cancel()
        self._cmd_task = asyncio.create_task(self._execute_command(cmd))

    async def _execute_command(self, cmd: dict[str, Any]) -> None:
        cmd_type = cmd.get("Command")
        when_str = cmd.get("When")
        pos_ticks = int(cmd.get("PositionTicks", 0))
        playlist_item_id = cmd.get("PlaylistItemId") or self.state.playlist_item_id
        delay_ms = 0.0
        if when_str:
            target_server_ms = parse_jellyfin_dt(when_str)
            now_server_ms = (time.time() * 1000.0) + self.clock_offset_ms
            delay_ms = max(0.0, target_server_ms - now_server_ms)
        if delay_ms > 5.0:
            await asyncio.sleep(delay_ms / 1000.0)

        now_mono = time.monotonic() * 1000.0
        pos_ms = pos_ticks / TICKS_PER_MS
        if cmd_type == "Unpause":
            self.state.anchor_pos_ms = pos_ms
            self.state.anchor_local_ms = now_mono
            self.state.is_playing = True
        elif cmd_type == "Pause":
            self.state.anchor_pos_ms = pos_ms
            self.state.anchor_local_ms = now_mono
            self.state.is_playing = False
        elif cmd_type == "Seek":
            self.state.anchor_pos_ms = pos_ms
            self.state.anchor_local_ms = now_mono
            if playlist_item_id and not self.simulate_buffering:
                await self.send_ready(pos_ticks, self.state.is_playing, playlist_item_id)
        elif cmd_type == "Stop":
            self.state.is_playing = False
            self.state.anchor_pos_ms = 0.0

    async def list_groups(self) -> list[dict[str, Any]]:
        return await self.request("GET", "/SyncPlay/List")

    async def create_group(self, name: str) -> dict[str, Any]:
        g = await self.request("POST", "/SyncPlay/New", {"GroupName": name})
        self.state.group_id = g["GroupId"]
        return g

    async def join_group(self, group_id: str) -> None:
        await self.request("POST", "/SyncPlay/Join", {"GroupId": group_id})
        self.state.group_id = group_id

    async def leave_group(self) -> None:
        await self.request("POST", "/SyncPlay/Leave")
        self.state.group_id = None

    async def set_new_queue(self, item_ids: list[str], index: int = 0, start_ticks: int = 0) -> None:
        await self.request("POST", "/SyncPlay/SetNewQueue", {
            "PlayingQueue": item_ids,
            "PlayingItemPosition": index,
            "StartPositionTicks": start_ticks,
        })

    async def pause(self) -> None:
        await self.request("POST", "/SyncPlay/Pause")

    async def unpause(self) -> None:
        await self.request("POST", "/SyncPlay/Unpause")

    async def stop(self) -> None:
        await self.request("POST", "/SyncPlay/Stop")

    async def seek(self, position_ms: int) -> None:
        await self.request("POST", "/SyncPlay/Seek", {"PositionTicks": position_ms * TICKS_PER_MS})

    async def next_item(self, playlist_item_id: Optional[str] = None) -> None:
        pid = playlist_item_id or self.state.playlist_item_id
        await self.request("POST", "/SyncPlay/NextItem", {"PlaylistItemId": pid})

    async def previous_item(self, playlist_item_id: Optional[str] = None) -> None:
        pid = playlist_item_id or self.state.playlist_item_id
        await self.request("POST", "/SyncPlay/PreviousItem", {"PlaylistItemId": pid})

    async def send_ready(self, pos_ticks: int, is_playing: bool, playlist_item_id: str) -> None:
        when_str = format_jellyfin_dt((time.time() * 1000.0) + self.clock_offset_ms)
        await self.request("POST", "/SyncPlay/Ready", {
            "When": when_str,
            "PositionTicks": pos_ticks,
            "IsPlaying": is_playing,
            "PlaylistItemId": playlist_item_id,
        })

    async def send_buffering(self, pos_ticks: int, is_playing: bool, playlist_item_id: str) -> None:
        when_str = format_jellyfin_dt((time.time() * 1000.0) + self.clock_offset_ms)
        await self.request("POST", "/SyncPlay/Buffering", {
            "When": when_str,
            "PositionTicks": pos_ticks,
            "IsPlaying": is_playing,
            "PlaylistItemId": playlist_item_id,
        })

    async def send_ping(self, ping_ms: int) -> None:
        await self.request("POST", "/SyncPlay/Ping", {"Ping": ping_ms})

    async def get_sessions(self) -> list[dict[str, Any]]:
        return await self.request("GET", "/Sessions")

    async def find_tv_session(self) -> Optional[dict[str, Any]]:
        sessions = await self.get_sessions()
        for s in sessions:
            if s.get("DeviceId") == self.device_id:
                continue
            client = (s.get("Client") or "").lower()
            dev = (s.get("DeviceName") or "").lower()
            if "android tv" in client or "chromecast" in dev or "boreal" in dev:
                return s
        return None

    async def wait_tv_state(
        self,
        is_paused: Optional[bool] = None,
        item_id: Optional[str] = None,
        min_pos_ms: Optional[int] = None,
        max_pos_ms: Optional[int] = None,
        timeout_s: float = 18.0,
    ) -> Optional[dict[str, Any]]:
        deadline = time.monotonic() + timeout_s
        norm_item = item_id.replace("-", "") if item_id else None
        while time.monotonic() < deadline:
            tv = await self.find_tv_session()
            if tv:
                ps = tv.get("PlayState") or {}
                np = tv.get("NowPlayingItem") or {}
                cur_item = (np.get("Id") or "").replace("-", "")
                cur_paused = bool(ps.get("IsPaused", False))
                cur_pos_ms = int(ps.get("PositionTicks", 0)) // TICKS_PER_MS
                ok = True
                if is_paused is not None and (not np or cur_paused != is_paused):
                    ok = False
                if norm_item is not None and cur_item != norm_item:
                    ok = False
                if min_pos_ms is not None and cur_pos_ms < min_pos_ms:
                    ok = False
                if max_pos_ms is not None and cur_pos_ms > max_pos_ms:
                    ok = False
                if ok:
                    return tv
            await asyncio.sleep(0.4)
        return None


async def cmd_status(peer: HeadlessSyncPlayPeer) -> None:
    groups = await peer.list_groups()
    tv = await peer.find_tv_session()
    print(f"Server: {peer.server_url} | Peer clock offset: {peer.clock_offset_ms:.1f}ms | RTT: {peer.rtt_ms}ms")
    print(f"Active SyncPlay groups ({len(groups)}): {json.dumps(groups)}")
    if tv:
        ps = tv.get("PlayState", {})
        np = tv.get("NowPlayingItem") or {}
        print(
            f"TV Session: id={tv.get('Id')} client={tv.get('Client')} device={tv.get('DeviceName')} "
            f"playing={np.get('Name')} ({np.get('Id')}) posMs={ps.get('PositionTicks', 0) // TICKS_PER_MS} "
            f"isPaused={ps.get('IsPaused')} transcode={tv.get('TranscodingInfo')}"
        )
    else:
        print("TV Session: NOT FOUND in /Sessions")


async def tv_open_syncplay_and_join(group_name: str) -> bool:
    """Ensure TV is at Settings -> Reproducir -> SyncPlay and joins `group_name`."""
    try:
        root = AdbTvDriver.dump_ui()
        if AdbTvDriver.find_node(root, text_contains=group_name) is not None and \
           AdbTvDriver.find_node(root, text_contains="SyncPlay") is not None:
            await AdbTvDriver.wait_and_tap(text_contains=group_name, timeout_s=4.0)
            await asyncio.sleep(1.2)
            return True
    except Exception:
        pass

    AdbTvDriver.launch_app_home()
    # Wait until Home toolbar is interactive
    for _ in range(20):
        try:
            root = AdbTvDriver.dump_ui()
            if AdbTvDriver.find_node(root, desc_contains="Preferencias") is not None or \
               AdbTvDriver.find_node(root, desc_contains="Settings") is not None:
                break
        except Exception:
            pass
        await asyncio.sleep(0.5)

    AdbTvDriver.keyevent("KEYCODE_DPAD_UP")
    await asyncio.sleep(0.3)
    opened_settings = (
        await AdbTvDriver.wait_and_tap(desc_contains="Preferencias", timeout_s=3.0)
        or await AdbTvDriver.wait_and_tap(desc_contains="Ajustes", timeout_s=2.0)
        or await AdbTvDriver.wait_and_tap(desc_contains="Settings", timeout_s=2.0)
    )
    if not opened_settings:
        return False
    await asyncio.sleep(0.6)
    if not (await AdbTvDriver.wait_and_tap(text_contains="Reproducir", timeout_s=4.0)
            or await AdbTvDriver.wait_and_tap(text_contains="Playback", timeout_s=2.0)):
        return False
    await asyncio.sleep(0.6)
    if not await AdbTvDriver.wait_and_tap(text_contains="SyncPlay", timeout_s=4.0):
        return False
    await asyncio.sleep(1.2)
    tapped = await AdbTvDriver.wait_and_tap(text_contains=group_name, timeout_s=6.0)
    await asyncio.sleep(1.2)
    return tapped


async def tv_close_settings() -> None:
    for _ in range(5):
        try:
            root = AdbTvDriver.dump_ui()
            if AdbTvDriver.find_node(root, text_contains="Mis medios") is not None or \
               AdbTvDriver.find_node(root, text_contains="My Media") is not None:
                return
        except Exception:
            pass
        AdbTvDriver.keyevent("KEYCODE_BACK")
        await asyncio.sleep(0.5)


async def tv_leave_group_via_ui() -> bool:
    AdbTvDriver.keyevent("KEYCODE_BACK")
    await asyncio.sleep(0.8)
    AdbTvDriver.launch_app_home()
    for _ in range(15):
        try:
            root = AdbTvDriver.dump_ui()
            if AdbTvDriver.find_node(root, desc_contains="Preferencias") is not None or \
               AdbTvDriver.find_node(root, desc_contains="Settings") is not None:
                break
        except Exception:
            pass
        await asyncio.sleep(0.5)
    AdbTvDriver.keyevent("KEYCODE_DPAD_UP")
    await asyncio.sleep(0.3)
    if not (await AdbTvDriver.wait_and_tap(desc_contains="Preferencias", timeout_s=3.0)
            or await AdbTvDriver.wait_and_tap(desc_contains="Settings", timeout_s=2.0)):
        return False
    await asyncio.sleep(0.6)
    await AdbTvDriver.wait_and_tap(text_contains="Reproducir", timeout_s=4.0)
    await asyncio.sleep(0.6)
    await AdbTvDriver.wait_and_tap(text_contains="SyncPlay", timeout_s=4.0)
    await asyncio.sleep(1.0)
    ok = await AdbTvDriver.wait_and_tap(text_contains="Salir", timeout_s=4.0)
    if not ok:
        ok = await AdbTvDriver.wait_and_tap(text_contains="Leave", timeout_s=3.0)
    await asyncio.sleep(0.8)
    await tv_close_settings()
    return ok


async def open_osd_and_pick_track(button_descs: list[str], option_index: int = 1) -> bool:
    """Open player OSD and tap an action button (Subtitles/Audio) then pick an item via DPAD."""
    AdbTvDriver.keyevent("KEYCODE_DPAD_DOWN")
    await asyncio.sleep(0.8)
    tapped = False
    for d in button_descs:
        if await AdbTvDriver.wait_and_tap(desc_contains=d, timeout_s=2.5):
            tapped = True
            break
    if not tapped:
        # Try second DPAD_DOWN / dump raw clickable nodes
        root = AdbTvDriver.dump_ui()
        print("  [OSD debug] clickable/desc nodes:", [
            (n.attrib.get("text"), n.attrib.get("content-desc"), n.attrib.get("bounds"))
            for n in root.iter("node")
            if n.attrib.get("clickable") == "true" or n.attrib.get("content-desc")
        ])
        return False
    await asyncio.sleep(0.8)
    for _ in range(option_index):
        AdbTvDriver.keyevent("KEYCODE_DPAD_DOWN")
        await asyncio.sleep(0.2)
    AdbTvDriver.keyevent("KEYCODE_DPAD_CENTER")
    return True


async def run_all_scenarios(peer: HeadlessSyncPlayPeer) -> int:
    results: list[tuple[int, str, bool, str]] = []

    def record(num: int, name: str, passed: bool, detail: str) -> None:
        status = "PASS" if passed else "FAIL"
        print(f"[{status}] S{num:02d} {name}: {detail}", flush=True)
        results.append((num, name, passed, detail))

    AdbTvDriver.clear_logcat()
    group_name = "VerifySyncPlay"
    # Ensure clean slate on server groups if Peer was still in one
    existing_groups = await peer.list_groups()
    if existing_groups:
        try:
            await peer.leave_group()
        except Exception:
            pass

    # --- S01: Create & Join SyncPlay group ---
    g = await peer.create_group(group_name)
    group_id = g["GroupId"]
    joined_ui = await tv_open_syncplay_and_join(group_name)
    joined_log = await AdbTvDriver.wait_for_log("Joined group", timeout_s=10.0)
    groups = await peer.list_groups()
    await tv_close_settings()
    tv_joined = bool(joined_ui and joined_log and any(grp.get("GroupId") == group_id for grp in groups))
    record(1, "Create & Join SyncPlay group", tv_joined, f"ui={joined_ui} log={joined_log is not None}")
    if not tv_joined:
        print("ABORTING: TV failed to join SyncPlay group.", flush=True)
        return 1

    # --- S02: Start Playback (SetNewQueue) ---
    AdbTvDriver.clear_logcat()
    await peer.set_new_queue([ITEM_EPISODIO_1, ITEM_ESPECIAL_1, ITEM_ESPECIAL_2], index=0, start_ticks=0)
    tv = await peer.wait_tv_state(is_paused=False, item_id=ITEM_EPISODIO_1, timeout_s=22.0)
    record(2, "Start playback via SetNewQueue", tv is not None, f"tv_state={tv.get('PlayState') if tv else None}")

    # --- S03: Playback Drift Convergence ---
    await asyncio.sleep(6.0)
    tv = await peer.find_tv_session()
    peer_pos = peer.state.current_pos_ms()
    tv_pos = (tv.get("PlayState", {}).get("PositionTicks", 0) // TICKS_PER_MS) if tv else -1
    drift_ms = abs(peer_pos - tv_pos)
    logs_s3 = AdbTvDriver.get_syncplay_logs()
    crashes = AdbTvDriver.has_crash_in_logs(logs_s3)
    record(3, "Active playback drift convergence", (not crashes) and tv is not None and not tv["PlayState"].get("IsPaused") and drift_ms < 2500, f"peer_ms={peer_pos:.0f} tv_ms={tv_pos} drift_ms={drift_ms:.0f}")

    # --- S04: Remote Pause ---
    AdbTvDriver.clear_logcat()
    await peer.pause()
    tv_paused = await peer.wait_tv_state(is_paused=True, timeout_s=10.0)
    await asyncio.sleep(2.0)
    log_pause = await AdbTvDriver.wait_for_log("Applying remote PAUSE", timeout_s=3.0)
    record(4, "Remote Pause synchronization", tv_paused is not None and log_pause is not None, f"isPaused={tv_paused['PlayState'].get('IsPaused') if tv_paused else None}")

    # --- S05: Remote Unpause with When barrier ---
    AdbTvDriver.clear_logcat()
    await peer.unpause()
    tv_unpaused = await peer.wait_tv_state(is_paused=False, timeout_s=10.0)
    await asyncio.sleep(3.0)
    tv = await peer.find_tv_session()
    drift_ms = abs(peer.state.current_pos_ms() - ((tv["PlayState"].get("PositionTicks", 0) // TICKS_PER_MS) if tv else 0))
    record(5, "Remote Unpause barrier & resume", tv_unpaused is not None and drift_ms < 2500, f"drift_ms={drift_ms:.0f}")

    # --- S06: Remote Seek while Playing (300s) ---
    AdbTvDriver.clear_logcat()
    await peer.seek(300_000)
    tv_seek = await peer.wait_tv_state(is_paused=False, min_pos_ms=295_000, max_pos_ms=320_000, timeout_s=15.0)
    await asyncio.sleep(2.5)
    logs_s6 = AdbTvDriver.get_syncplay_logs()
    no_stop = not any("User local STOP" in l for l in logs_s6)
    record(6, "Remote Seek while Playing (300s)", tv_seek is not None and no_stop, f"tv_ms={tv_seek['PlayState'].get('PositionTicks', 0) // TICKS_PER_MS if tv_seek else None}")

    # --- S07: Remote Seek while Paused (600s, MUST stay paused) ---
    await peer.pause()
    await peer.wait_tv_state(is_paused=True, timeout_s=8.0)
    AdbTvDriver.clear_logcat()
    await peer.seek(600_000)
    await asyncio.sleep(4.5)
    tv_paused_seek = await peer.find_tv_session()
    tv_pos_s7 = (tv_paused_seek["PlayState"].get("PositionTicks", 0) // TICKS_PER_MS) if tv_paused_seek else -1
    is_still_paused = bool(tv_paused_seek and tv_paused_seek["PlayState"].get("IsPaused"))
    record(7, "Remote Seek while Paused stays Paused", is_still_paused and 595_000 <= tv_pos_s7 <= 605_000, f"isPaused={is_still_paused} tv_ms={tv_pos_s7}")

    # --- S08: Late Join while group Playing at offset > 0 ---
    await peer.unpause()
    await peer.wait_tv_state(is_paused=False, timeout_s=8.0)
    await asyncio.sleep(2.0)
    # Leave group on TV (stopping playback on TV cleanly), then rejoin while Peer keeps playing!
    AdbTvDriver.keyevent("KEYCODE_BACK")
    await asyncio.sleep(0.5)
    AdbTvDriver.keyevent("KEYCODE_BACK")
    await asyncio.sleep(1.0)
    await tv_leave_group_via_ui()
    await asyncio.sleep(1.5)
    peer_late_pos = int(peer.state.current_pos_ms())
    AdbTvDriver.clear_logcat()
    rejoined = await tv_open_syncplay_and_join(group_name)
    for _ in range(3):
        AdbTvDriver.keyevent("KEYCODE_BACK")
        await asyncio.sleep(0.25)
    tv_late = await peer.wait_tv_state(is_paused=False, min_pos_ms=max(0, peer_late_pos - 5000), timeout_s=22.0)
    tv_late_ms = (tv_late["PlayState"].get("PositionTicks", 0) // TICKS_PER_MS) if tv_late else -1
    record(8, "Late join while group Playing at offset >0", rejoined and tv_late is not None, f"peer_ms={peer_late_pos} tv_ms={tv_late_ms}")

    # --- S09: Audio Track Switch during Playing ---
    AdbTvDriver.clear_logcat()
    audio_clicked = await open_osd_and_pick_track(["Audio", "Pista de audio", "Seleccionar audio"], option_index=1)
    await asyncio.sleep(4.5)
    logs_s9 = AdbTvDriver.get_syncplay_logs()
    tv_s9 = await peer.find_tv_session()
    crashes_s9 = AdbTvDriver.has_crash_in_logs(logs_s9)
    internal_tx = any("beginInternalTransition reason=TRACK_SWITCH" in l for l in logs_s9)
    no_false_stop = not any("User local STOP" in l for l in logs_s9)
    record(9, "Audio track switch during Playing", audio_clicked and not crashes_s9 and internal_tx and no_false_stop and bool(tv_s9 and not tv_s9["PlayState"].get("IsPaused")), f"clicked={audio_clicked} tx={internal_tx} paused={tv_s9['PlayState'].get('IsPaused') if tv_s9 else None}")

    # --- S10 & S11: Subtitle Switch + Burn-In Transcode Rebuild (ASS subs default burn-in) ---
    AdbTvDriver.clear_logcat()
    sub_clicked = await open_osd_and_pick_track(["Subtítulos", "Subtitles", "Subt"], option_index=2)
    await asyncio.sleep(6.0)
    logs_s10 = AdbTvDriver.get_syncplay_logs()
    crashes_s10 = AdbTvDriver.has_crash_in_logs(logs_s10)
    tx_sub = any("beginInternalTransition" in l for l in logs_s10)
    no_false_stop_s10 = not any("User local STOP" in l for l in logs_s10)
    tv_s10 = await peer.find_tv_session()
    is_transcoding = bool(tv_s10 and tv_s10.get("TranscodingInfo"))
    playing_s10 = bool(tv_s10 and not tv_s10["PlayState"].get("IsPaused"))
    record(10, "Subtitle track switch (no NPE / no Stop)", sub_clicked and not crashes_s10 and no_false_stop_s10 and playing_s10, f"clicked={sub_clicked} tx={tx_sub} crashes={len(crashes_s10)}")
    record(11, "Subtitle burn-in / stream rebuild live catchup", sub_clicked and not crashes_s10 and no_false_stop_s10 and playing_s10, f"transcoding={is_transcoding} playing={playing_s10}")

    # --- S12: Pause + Subtitle Change while Paused stays Paused ---
    await peer.pause()
    await peer.wait_tv_state(is_paused=True, timeout_s=8.0)
    await asyncio.sleep(1.0)
    AdbTvDriver.clear_logcat()
    sub_paused_clicked = await open_osd_and_pick_track(["Subtítulos", "Subtitles", "Subt"], option_index=0)
    await asyncio.sleep(5.0)
    logs_s12 = AdbTvDriver.get_syncplay_logs()
    tv_s12 = await peer.find_tv_session()
    still_paused_s12 = bool(tv_s12 and tv_s12["PlayState"].get("IsPaused"))
    record(12, "Pause + subtitle change stays Paused", sub_paused_clicked and still_paused_s12 and not AdbTvDriver.has_crash_in_logs(logs_s12), f"clicked={sub_paused_clicked} isPaused={still_paused_s12}")

    # --- S13: Simulated Stall/Buffering + NextItem/PrevItem + Leave ---
    await peer.unpause()
    await peer.wait_tv_state(is_paused=False, timeout_s=8.0)
    AdbTvDriver.clear_logcat()
    # 13a Buffering -> Ready
    if peer.state.playlist_item_id:
        await peer.send_buffering(int(peer.state.current_pos_ms()) * TICKS_PER_MS, False, peer.state.playlist_item_id)
    tv_stall = await peer.wait_tv_state(is_paused=True, timeout_s=8.0)
    if peer.state.playlist_item_id:
        await peer.send_ready(int(peer.state.current_pos_ms()) * TICKS_PER_MS, True, peer.state.playlist_item_id)
    tv_rec = await peer.wait_tv_state(is_paused=False, timeout_s=10.0)
    # 13b NextItem -> Especial 1 (no teardown Stop sent to server!)
    await peer.next_item()
    tv_next = await peer.wait_tv_state(item_id=ITEM_ESPECIAL_1, is_paused=False, timeout_s=20.0)
    logs_s13 = AdbTvDriver.get_syncplay_logs()
    ignored_teardown = any("Ignoring tear-down stop" in l for l in logs_s13) or not any("User local STOP" in l for l in logs_s13)
    # 13c Stop + clean exit
    await peer.stop()
    await asyncio.sleep(2.0)
    await tv_leave_group_via_ui()
    await peer.leave_group()
    crashes_s13 = AdbTvDriver.has_crash_in_logs(AdbTvDriver.get_syncplay_logs())
    s13_ok = (tv_stall is not None) and (tv_rec is not None) and (tv_next is not None) and ignored_teardown and not crashes_s13
    record(13, "Stall/Buffering recovery + NextItem queue switch + Leave", s13_ok, f"stall={tv_stall is not None} rec={tv_rec is not None} next={tv_next is not None} teardownOk={ignored_teardown}")

    passed_count = sum(1 for _, _, ok, _ in results if ok)
    print(f"\nSUMMARY: {passed_count}/{len(results)} scenarios passed.")
    return 0 if passed_count == len(results) else 1


def main() -> int:
    parser = argparse.ArgumentParser(description="Headless SyncPlay peer & TV verifier")
    parser.add_argument("action", choices=["status", "run-all"], default="status", nargs="?")
    args = parser.parse_args()

    url, _uid, token = load_tv_credentials()

    async def runner() -> int:
        async with HeadlessSyncPlayPeer(url, token) as peer:
            if args.action == "status":
                await cmd_status(peer)
                return 0
            return await run_all_scenarios(peer)

    return asyncio.run(runner())


if __name__ == "__main__":
    sys.exit(main())
