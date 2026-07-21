from __future__ import annotations

import os
import signal
import sys
from multiprocessing.process import BaseProcess
from typing import Any, Protocol


class ProcessTreeError(RuntimeError):
    pass


class ProcessTreeHandle(Protocol):
    def terminate(self, exit_code: int = 1) -> None: ...

    def close(self) -> None: ...


class PosixProcessGroup:
    def __init__(self, pgid: int) -> None:
        self.pgid = pgid

    def terminate(self, exit_code: int = 1) -> None:
        del exit_code
        try:
            os.killpg(self.pgid, signal.SIGTERM)
        except ProcessLookupError:
            return

    def kill(self) -> None:
        try:
            os.killpg(self.pgid, signal.SIGKILL)
        except ProcessLookupError:
            return

    def close(self) -> None:
        return


if sys.platform == "win32":
    import ctypes
    from ctypes import wintypes

    JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000
    JobObjectExtendedLimitInformation = 9
    PROCESS_TERMINATE = 0x0001
    PROCESS_SET_QUOTA = 0x0100
    PROCESS_QUERY_LIMITED_INFORMATION = 0x1000

    class JOBOBJECT_BASIC_LIMIT_INFORMATION(ctypes.Structure):
        _fields_ = [
            ("PerProcessUserTimeLimit", ctypes.c_longlong),
            ("PerJobUserTimeLimit", ctypes.c_longlong),
            ("LimitFlags", wintypes.DWORD),
            ("MinimumWorkingSetSize", ctypes.c_size_t),
            ("MaximumWorkingSetSize", ctypes.c_size_t),
            ("ActiveProcessLimit", wintypes.DWORD),
            ("Affinity", ctypes.c_size_t),
            ("PriorityClass", wintypes.DWORD),
            ("SchedulingClass", wintypes.DWORD),
        ]

    class IO_COUNTERS(ctypes.Structure):
        _fields_ = [
            ("ReadOperationCount", ctypes.c_ulonglong),
            ("WriteOperationCount", ctypes.c_ulonglong),
            ("OtherOperationCount", ctypes.c_ulonglong),
            ("ReadTransferCount", ctypes.c_ulonglong),
            ("WriteTransferCount", ctypes.c_ulonglong),
            ("OtherTransferCount", ctypes.c_ulonglong),
        ]

    class JOBOBJECT_EXTENDED_LIMIT_INFORMATION(ctypes.Structure):
        _fields_ = [
            ("BasicLimitInformation", JOBOBJECT_BASIC_LIMIT_INFORMATION),
            ("IoInfo", IO_COUNTERS),
            ("ProcessMemoryLimit", ctypes.c_size_t),
            ("JobMemoryLimit", ctypes.c_size_t),
            ("PeakProcessMemoryUsed", ctypes.c_size_t),
            ("PeakJobMemoryUsed", ctypes.c_size_t),
        ]

    class WindowsJobObject:
        def __init__(self) -> None:
            kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
            kernel32.CreateJobObjectW.argtypes = [ctypes.c_void_p, wintypes.LPCWSTR]
            kernel32.CreateJobObjectW.restype = wintypes.HANDLE
            kernel32.SetInformationJobObject.argtypes = [
                wintypes.HANDLE,
                ctypes.c_int,
                ctypes.c_void_p,
                wintypes.DWORD,
            ]
            kernel32.SetInformationJobObject.restype = wintypes.BOOL
            kernel32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
            kernel32.OpenProcess.restype = wintypes.HANDLE
            kernel32.AssignProcessToJobObject.argtypes = [wintypes.HANDLE, wintypes.HANDLE]
            kernel32.AssignProcessToJobObject.restype = wintypes.BOOL
            kernel32.TerminateJobObject.argtypes = [wintypes.HANDLE, wintypes.UINT]
            kernel32.TerminateJobObject.restype = wintypes.BOOL
            kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
            kernel32.CloseHandle.restype = wintypes.BOOL
            self._kernel32 = kernel32
            self._handle = kernel32.CreateJobObjectW(None, None)
            if not self._handle:
                raise ProcessTreeError(f"CreateJobObject failed: {ctypes.get_last_error()}")
            info = JOBOBJECT_EXTENDED_LIMIT_INFORMATION()
            info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
            ok = kernel32.SetInformationJobObject(
                self._handle,
                JobObjectExtendedLimitInformation,
                ctypes.byref(info),
                ctypes.sizeof(info),
            )
            if not ok:
                error = ctypes.get_last_error()
                self.close()
                raise ProcessTreeError(f"SetInformationJobObject failed: {error}")

        def assign(self, pid: int) -> None:
            process = self._kernel32.OpenProcess(
                PROCESS_TERMINATE | PROCESS_SET_QUOTA | PROCESS_QUERY_LIMITED_INFORMATION,
                False,
                pid,
            )
            if not process:
                raise ProcessTreeError(f"OpenProcess failed: {ctypes.get_last_error()}")
            try:
                if not self._kernel32.AssignProcessToJobObject(self._handle, process):
                    raise ProcessTreeError(
                        f"AssignProcessToJobObject failed: {ctypes.get_last_error()}"
                    )
            finally:
                self._kernel32.CloseHandle(process)

        def terminate(self, exit_code: int = 1) -> None:
            if self._handle and not self._kernel32.TerminateJobObject(self._handle, exit_code):
                error = ctypes.get_last_error()
                if error:
                    raise ProcessTreeError(f"TerminateJobObject failed: {error}")

        def close(self) -> None:
            if getattr(self, "_handle", None):
                self._kernel32.CloseHandle(self._handle)
                self._handle = None


def attach_process_tree(
    process: BaseProcess,
    *,
    ready_event: Any,
    start_gate: Any,
    timeout_seconds: float = 5,
) -> ProcessTreeHandle:
    if not ready_event.wait(timeout_seconds):
        process.terminate()
        process.join(timeout=timeout_seconds)
        raise ProcessTreeError("worker did not reach process-tree start gate")
    if process.pid is None:
        raise ProcessTreeError("worker process has no pid")
    if sys.platform == "win32":
        handle = WindowsJobObject()
        try:
            handle.assign(process.pid)
        except Exception:
            handle.close()
            process.terminate()
            process.join(timeout=timeout_seconds)
            raise
    else:
        handle = PosixProcessGroup(process.pid)
    start_gate.set()
    return handle
