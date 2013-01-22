/*
 * The MIT License
 *
 * Copyright (c) 2009-, Sun Microsystems, Inc., CloudBees, Inc.
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

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.StringArray;
import static com.sun.akuma.CLibrary.LIBC;

import java.io.FileWriter;
import java.io.IOException;
import java.io.File;
import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Forks a copy of the current process into the background.
 *
 * <p>
 * Because of the fork/exec involved in doing this, your code has to call Daemonizer in a certain sequence.
 * Specifically, from your main method:
 * <pre>
 * public static void main(String[] args) {
 *     Daemon d = new Daemon();
 *     if(d.isDaemonized()) {
 *         // perform initialization as a daemon
 *         // this involves in closing file descriptors, recording PIDs, etc.
 *         d.{@linkplain #init() init}();
 *     } else {
 *         // if you are already daemonized, no point in daemonizing yourself again,
 *         // so do this only when you aren't daemonizing.
 *         if(you decide to launch a copy into background) {
 *             d.daemonize(...);
 *             System.exit(0);
 *         }
 *     }
 *
 *     // your normal main code follows
 *     // this part can be executed in two ways
 *     // 1) the user runs your process in the foreground
 *     // 2) you decided to daemonize yourself, in which case the newly forked daemon will execute this code,
 *     //    while the originally executed foreground Java process exits before it gets here.
 *     ...
 * }
 * </pre>
 *
 * <p>
 * Alternatively, your main class can extend from Daemon, so that you can customize some of the behaviors.
 *
 * @author Kohsuke Kawaguchi
 */
public class Daemon {
    /**
     * Do all the necessary steps in one go.
     *
     * @param daemonize
     *      Parse the command line arguments and if the application should be
     *      daemonized, pass in true.
     */
    public void all(boolean daemonize) throws Exception {
        if(isDaemonized())
            init();
        else {
            if(daemonize) {
                daemonize();
                System.exit(0);
            }
        }
    }

    /**
     * Returns true if the current process is already launched as a daemon
     * via {@link #daemonize()}.
     */
    public boolean isDaemonized() {
        return System.getProperty(Daemon.class.getName())!=null;
    }

    /**
     * Relaunches the JVM with the exact same arguments into the daemon.
     */
    public void daemonize() throws IOException {
        daemonize(JavaVMArguments.current());
    }

    /**
     * Relaunches the JVM with the given arguments into the daemon.
     */
    public void daemonize(JavaVMArguments args) {
        if(isDaemonized())
            throw new IllegalStateException("Already running as a daemon");

        if (System.getProperty("com.sun.management.jmxremote.port") != null) {
            try {
                Method m = Class.forName("sun.management.Agent").getDeclaredMethod("stopRemoteManagementAgent");
                m.setAccessible(true);
                m.invoke(null);
            } catch (Exception x) {
                LOGGER.log(Level.SEVERE, "could not simulate jcmd $$ ManagementAgent.stop (JENKINS-14529)", x);
            }
        }

        // let the child process now that it's a daemon
        args.setSystemProperty(Daemon.class.getName(),"daemonized");

        // prepare for a fork
        String exe = getCurrentExecutable();
        StringArray sa = args.toStringArray();

        int i = LIBC.fork();
        if(i<0) {
            LIBC.perror("initial fork failed");
            System.exit(-1);
        }
        if(i==0) {
            // with fork, we lose all the other critical threads, to exec to Java again
            LIBC.execv(exe,sa);
            System.err.println("exec failed");
            LIBC.perror("initial exec failed");
            System.exit(-1);
        }

        // parent exits
    }

    /**
     * Overwrites the current process with a new Java VM with the given JVM arguments.
     */
    public static void selfExec(JavaVMArguments args) {
        LIBC.execv(getCurrentExecutable(), args.toStringArray());
    }

    /**
     * Prepares the current process to act as a daemon.
     * The daemon's PID is written to the file <code>/var/run/daemon.pid</code>.
     */
    public void init() throws Exception {
        init("/var/run/daemon.pid");
    }
    
    /**
     * Prepares the current process to act as a daemon.
     * @param pidFile the filename to which the daemon's PID is written; 
     * or, <code>null</code> to skip writing a PID file.
     */
    @SuppressWarnings({"OctalInteger"})
    public void init(String pidFile) throws Exception {
        // start a new process session
        LIBC.setsid();

        closeDescriptors();

        chdirToRoot();
        if (pidFile != null) writePidFile(pidFile);
    }

    /**
     * Closes inherited file descriptors.
     *
     * <p>
     * This method can be overridden to no-op in a subtype. Useful for debugging daemon processes
     * when they don't work correctly.
     */
    protected void closeDescriptors() throws IOException {
        if(!Boolean.getBoolean(Daemon.class.getName()+".keepDescriptors")) {
            System.out.close();
            System.err.close();
            System.in.close();
        }

        // ideally we'd like to close all other descriptors, but that would close
        // jar files used as classpath, and break JVM.
    }

    /**
     * change directory to '/' to avoid locking directories.
     */
    protected void chdirToRoot() {
        LIBC.chdir("/");
        System.setProperty("user.dir","/");
    }

    /**
     * Writes out the PID of the current process to the specified file.
     * @param pidFile the filename to write the PID to.
     */
    protected void writePidFile(String pidFile) throws IOException {
        try {
            FileWriter fw = new FileWriter(pidFile);
            fw.write(String.valueOf(LIBC.getpid()));
            fw.close();
        } catch (IOException e) {
            // if failed to write, keep going because maybe we are run from non-root
        }
    }

    /**
     * Gets the current executable name.
     */
    public static String getCurrentExecutable() {
        int pid = LIBC.getpid();
        String name = "/proc/" + pid + "/exe";
        File exe = new File(name);
        if(exe.exists()) {
            try {
                String path = resolveSymlink(exe);
                if (path!=null)     return path;
            } catch (IOException e) {
                LOGGER.log(Level.FINE,"Failed to resolve symlink "+exe,e);
            }
            return name;
        }

        // cross-platform fallback
        return System.getProperty("java.home")+"/bin/java";
    }

    private static String resolveSymlink(File link) throws IOException {
        String filename = link.getAbsolutePath();

        for (int sz=512; sz < 65536; sz*=2) {
            Memory m = new Memory(sz);
            int r = LIBC.readlink(filename,m,new NativeLong(sz));
            if (r<0) {
                int err = Native.getLastError();
                if (err==22/*EINVAL --- but is this really portable?*/)
                    return null; // this means it's not a symlink
                throw new IOException("Failed to readlink "+link+" error="+ err+" "+ LIBC.strerror(err));
            }

            if (r==sz)
                continue;   // buffer too small

            byte[] buf = new byte[r];
            m.read(0,buf,0,r);
            return new String(buf);
        }

        throw new IOException("Failed to readlink "+link);
    }

    /**
     * Flavor of {@link Daemon} that doesn't change the current directory.
     *
     * <p>
     * This turns out to be often more useful as JavaVM can take lot of arguments and system properties
     * that use paths, and when we CD they won't work.
     */
    public static class WithoutChdir extends Daemon {
        @Override
        protected void chdirToRoot() {
            // noop
        }
    }

    private static final Logger LOGGER = Logger.getLogger(Daemon.class.getName());
}
