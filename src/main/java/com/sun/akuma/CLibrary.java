/*
 * The MIT License
 *
 * Copyright (c) 2009-, Sun Microsystems, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.sun.akuma;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.NativeLong;
import com.sun.jna.StringArray;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.PointerType;
import com.sun.jna.ptr.IntByReference;

/**
 * GNU C library.
 */
public interface CLibrary extends Library {
    int fork();
    int kill(int pid, int signum);
    int setsid();
    int setuid(short newuid);
    int setgid(short newgid);
    int umask(int mask);
    int getpid();
    int getppid();
    int chdir(String dir);
    int execv(String file, StringArray args);
    int setenv(String name, String value);
    int unsetenv(String name);
    void perror(String msg);
    String strerror(int errno);

    // this is listed in http://developer.apple.com/DOCUMENTATION/Darwin/Reference/ManPages/man3/sysctlbyname.3.html
    // but not in http://www.gnu.org/software/libc/manual/html_node/System-Parameters.html#index-sysctl-3493
    // perhaps it is only supported on BSD?
    int sysctlbyname(String name, Pointer oldp, IntByReference oldlenp, Pointer newp, IntByReference newlen);

    int sysctl(int[] mib, int nameLen, Pointer oldp, IntByReference oldlenp, Pointer newp, IntByReference newlen);

    int sysctlnametomib(String name, Pointer mibp, IntByReference size);

    public class FILE extends PointerType {
        public FILE() {
        }

        public FILE(Pointer pointer) {
            super(pointer);
        }
    }

    // Additional C functions we need on Solaris 64bit to seek to a place above Long.MAX_VALUE
    FILE fopen(String fileName, String mode);
    int fseek(FILE file, long offset, int whence);
    long ftell(FILE file);
    int fread(Pointer buf, int size, int count, FILE file);
    int fclose(FILE file);

    /**
     * Read a symlink. The name will be copied into the specified memory, and returns the number of
     * bytes copied. The string is not null-terminated.
     *
     * @return
     *      if the return value equals size, the caller needs to retry with a bigger buffer.
     *      If -1, error.
     */
    int readlink(String filename, Memory buffer, NativeLong size);

    public static final CLibrary LIBC = (CLibrary) Native.loadLibrary("c",CLibrary.class);
}
