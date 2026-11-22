# Windows Credential Manager helper for the CallGuard release keystore.
# The password is stored as a generic credential and never printed by these functions.

$ErrorActionPreference = 'Stop'

if (-not ('CallGuard.WinCred' -as [type])) {
    Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;

namespace CallGuard {
    public static class WinCred {
        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        public struct CREDENTIAL {
            public int Flags;
            public int Type;
            public string TargetName;
            public string Comment;
            public long LastWritten;
            public int CredentialBlobSize;
            public IntPtr CredentialBlob;
            public int Persist;
            public int AttributeCount;
            public IntPtr Attributes;
            public string TargetAlias;
            public string UserName;
        }

        [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        private static extern bool CredWrite(ref CREDENTIAL credential, int flags);

        [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        private static extern bool CredRead(string target, int type, int flags, out IntPtr credential);

        [DllImport("advapi32.dll", SetLastError = true)]
        private static extern bool CredDelete(string target, int type, int flags);

        [DllImport("advapi32.dll", SetLastError = true)]
        private static extern void CredFree(IntPtr buffer);

        private const int CredTypeGeneric = 1;
        private const int CredPersistLocalMachine = 2;
        private const int ErrorNotFound = 1168;

        public static void Write(string target, string userName, string password) {
            byte[] bytes = System.Text.Encoding.Unicode.GetBytes(password);
            CREDENTIAL cred = new CREDENTIAL {
                Type = CredTypeGeneric,
                TargetName = target,
                UserName = userName,
                Persist = CredPersistLocalMachine,
                CredentialBlobSize = bytes.Length,
                CredentialBlob = Marshal.AllocHGlobal(bytes.Length)
            };
            try {
                Marshal.Copy(bytes, 0, cred.CredentialBlob, bytes.Length);
                if (!CredWrite(ref cred, 0)) {
                    throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
                }
            } finally {
                Marshal.FreeHGlobal(cred.CredentialBlob);
            }
        }

        public static string Read(string target) {
            IntPtr ptr;
            if (!CredRead(target, CredTypeGeneric, 0, out ptr)) {
                int err = Marshal.GetLastWin32Error();
                if (err == ErrorNotFound) return null;
                throw new System.ComponentModel.Win32Exception(err);
            }
            try {
                CREDENTIAL cred = (CREDENTIAL)Marshal.PtrToStructure(ptr, typeof(CREDENTIAL));
                return Marshal.PtrToStringUni(cred.CredentialBlob, cred.CredentialBlobSize / 2);
            } finally {
                CredFree(ptr);
            }
        }

        public static void Delete(string target) {
            if (!CredDelete(target, CredTypeGeneric, 0)) {
                int err = Marshal.GetLastWin32Error();
                if (err != ErrorNotFound) throw new System.ComponentModel.Win32Exception(err);
            }
        }
    }
}
"@
}

$script:CallGuardCredentialTarget = 'CallGuard/Keystore'

function Get-CallGuardKeystorePassword {
    return [CallGuard.WinCred]::Read($script:CallGuardCredentialTarget)
}

function Set-CallGuardKeystorePassword {
    param([Parameter(Mandatory = $true)][string]$Password)
    [CallGuard.WinCred]::Write($script:CallGuardCredentialTarget, 'callguard', $Password)
}

function Remove-CallGuardKeystorePassword {
    [CallGuard.WinCred]::Delete($script:CallGuardCredentialTarget)
}
