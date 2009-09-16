package com.sun.akuma;

import com.sun.jna.StringArray;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import static com.sun.jna.Pointer.NULL;
import com.sun.jna.ptr.IntByReference;

import java.util.*;
import static java.util.logging.Level.FINEST;
import java.util.logging.Logger;
import java.io.IOException;
import java.io.FileInputStream;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.RandomAccessFile;
import java.io.DataInputStream;

import static com.sun.akuma.CLibrary.LIBC;

/**
 * List of arguments for Java VM and application.
 *
 * @author Kohsuke Kawaguchi
 */
public class JavaVMArguments extends ArrayList<String> {
    public JavaVMArguments() {
    }

    public JavaVMArguments(Collection<? extends String> c) {
        super(c);
    }

    public void removeSystemProperty(String name) {
        name = "-D"+name;
        String nameeq = name+'=';
        for (Iterator<String> itr = this.iterator(); itr.hasNext();) {
            String s =  itr.next();
            if(s.equals(name) || s.startsWith(nameeq))
                itr.remove();
        }
    }

    public void setSystemProperty(String name, String value) {
        removeSystemProperty(name);
        // index 0 is the executable name
        add(1,"-D"+name+"="+value);
    }

    /**
     * Removes the n items from the end.
     * Useful for removing all the Java arguments to rebuild them.
     */
    public void removeTail(int n) {
        removeAll(subList(size()-n,size()));
    }

    /*package*/ StringArray toStringArray() {
        return new StringArray(toArray(new String[size()]));
    }

    /**
     * Gets the process argument list of the current process.
     */
    public static JavaVMArguments current() throws IOException {
        return of(-1);
    }

    /**
     * Gets the process argument list of the specified process ID.
     *
     * @param pid
     *      -1 to indicate the current process.
     */
    public static JavaVMArguments of(int pid) throws IOException {
        String os = System.getProperty("os.name");
        if("Linux".equals(os))
            return ofLinux(pid);
        if("SunOS".equals(os))
            return ofSolaris(pid);
        if("Mac OS X".equals(os))
            return ofMac(pid);

        throw new UnsupportedOperationException("Unsupported Operating System "+os);
    }

    private static JavaVMArguments ofLinux(int pid) throws IOException {
        pid = resolvePID(pid);

        String cmdline = readFile(new File("/proc/" + pid + "/cmdline"));
        JavaVMArguments args = new JavaVMArguments(Arrays.asList(cmdline.split("\0")));

        // we don't want them inherited
        args.removeSystemProperty(Daemon.class.getName());
        args.removeSystemProperty(NetworkServer.class.getName()+".mode");
        return args;
    }

    private static int resolvePID(int pid) {
        if(pid==-1) pid=LIBC.getpid();
        return pid;
    }

    private static JavaVMArguments ofSolaris(int pid) throws IOException {
        pid = resolvePID(pid);
        RandomAccessFile psinfo = new RandomAccessFile(new File("/proc/"+pid+"/psinfo"),"r");
        try {
            // see http://cvs.opensolaris.org/source/xref/onnv/onnv-gate/usr/src/uts/common/sys/procfs.h
            //typedef struct psinfo {
            //	int	pr_flag;	/* process flags */
            //	int	pr_nlwp;	/* number of lwps in the process */
            //	pid_t	pr_pid;	/* process id */
            //	pid_t	pr_ppid;	/* process id of parent */
            //	pid_t	pr_pgid;	/* process id of process group leader */
            //	pid_t	pr_sid;	/* session id */
            //	uid_t	pr_uid;	/* real user id */
            //	uid_t	pr_euid;	/* effective user id */
            //	gid_t	pr_gid;	/* real group id */
            //	gid_t	pr_egid;	/* effective group id */
            //	uintptr_t	pr_addr;	/* address of process */
            //	size_t	pr_size;	/* size of process image in Kbytes */
            //	size_t	pr_rssize;	/* resident set size in Kbytes */
            //	dev_t	pr_ttydev;	/* controlling tty device (or PRNODEV) */
            //	ushort_t	pr_pctcpu;	/* % of recent cpu time used by all lwps */
            //	ushort_t	pr_pctmem;	/* % of system memory used by process */
            //	timestruc_t	pr_start;	/* process start time, from the epoch */
            //	timestruc_t	pr_time;	/* cpu time for this process */
            //	timestruc_t	pr_ctime;	/* cpu time for reaped children */
            //	char	pr_fname[PRFNSZ];	/* name of exec'ed file */
            //	char	pr_psargs[PRARGSZ];	/* initial characters of arg list */
            //	int	pr_wstat;	/* if zombie, the wait() status */
            //	int	pr_argc;	/* initial argument count */
            //	uintptr_t	pr_argv;	/* address of initial argument vector */
            //	uintptr_t	pr_envp;	/* address of initial environment vector */
            //	char	pr_dmodel;	/* data model of the process */
            //	lwpsinfo_t	pr_lwp;	/* information for representative lwp */
            //} psinfo_t;

            // see http://cvs.opensolaris.org/source/xref/onnv/onnv-gate/usr/src/uts/common/sys/types.h
            // for the size of the various datatype.

            // see http://cvs.opensolaris.org/source/xref/onnv/onnv-gate/usr/src/cmd/ptools/pargs/pargs.c
            // for how to read this information

            psinfo.seek(8);
            if(adjust(psinfo.readInt())!=pid)
                throw new IOException("psinfo PID mismatch");   // sanity check

            psinfo.seek(0xBC);  // now jump to pr_argc
            int argc = adjust(psinfo.readInt());
            int argp = adjust(psinfo.readInt());

            RandomAccessFile as = new RandomAccessFile(new File("/proc/"+pid+"/as"),"r");
            try {
                JavaVMArguments args = new JavaVMArguments();
                for( int n=0; n<argc; n++ ) {
                    // read a pointer to one entry
                    as.seek(to64(argp+n*4));
                    int p = adjust(as.readInt());

                    args.add(readLine(as, p, "argv["+ n +"]"));
                }
                return args;
            } finally {
                as.close();
            }
        } finally {
            psinfo.close();
        }
    }

    /**
     * {@link DataInputStream} reads a value in big-endian, so
     * convert it to the correct value on little-endian systems.
     */
    private static int adjust(int i) {
        if(IS_LITTLE_ENDIAN)
            return (i<<24) |((i<<8) & 0x00FF0000) | ((i>>8) & 0x0000FF00) | (i>>>24);
        else
            return i;
    }

    /**
     * int to long conversion with zero-padding.
     */
    private static long to64(int i) {
        return i&0xFFFFFFFFL;
    }

    private static String readLine(RandomAccessFile as, int p, String prefix) throws IOException {
        if(LOGGER.isLoggable(FINEST))
            LOGGER.finest("Reading "+prefix+" at "+p);

        as.seek(to64(p));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int ch,i=0;
        while((ch=as.read())>0) {
            if((++i)%100==0 && LOGGER.isLoggable(FINEST))
                LOGGER.finest(prefix +" is so far "+buf.toString());

            buf.write(ch);
        }
        String line = buf.toString();
        if(LOGGER.isLoggable(FINEST))
            LOGGER.finest(prefix+" was "+line);
        return line;
    }

    /**
     * Reads the entire file.
     */
    private static String readFile(File f) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        FileInputStream fin = new FileInputStream(f);
        try {
            int sz;
            byte[] buf = new byte[1024];

            while((sz=fin.read(buf))>=0) {
                baos.write(buf,0,sz);
            }

            return baos.toString();
        } finally {
            fin.close();
        }
    }

    /**
     * Mac support
     *
     * See http://developer.apple.com/qa/qa2001/qa1123.html
     * http://www.osxfaq.com/man/3/kvm_getprocs.ws
     * http://matburt.net/?p=16 (libkvm is removed from OSX)
     * where is kinfo_proc? http://lists.apple.com/archives/xcode-users/2008/Mar/msg00781.html
     *
     * This code uses sysctl to get the arg/env list:
     * http://www.psychofx.com/psi/trac/browser/psi/trunk/src/arch/macosx/macosx_process.c
     * which came from
     * http://www.opensource.apple.com/darwinsource/10.4.2/top-15/libtop.c
     *
     * sysctl is defined in libc.
     *
     * PS source code for Mac:
     * http://www.opensource.apple.com/darwinsource/10.4.1/adv_cmds-79.1/ps.tproj/
     */
    private static JavaVMArguments ofMac(int pid) {
        // local constants
        final int CTL_KERN = 1;
        final int KERN_ARGMAX = 8;
        final int KERN_PROCARGS2 = 49;
        final int sizeOfInt = Native.getNativeSize(int.class);
        IntByReference _ = new IntByReference();


        IntByReference argmaxRef = new IntByReference(0);
        IntByReference size = new IntByReference(sizeOfInt);

        // for some reason, I was never able to get sysctlbyname work.
//        if(LIBC.sysctlbyname("kern.argmax", argmaxRef.getPointer(), size, NULL, _)!=0)
        if(LIBC.sysctl(new int[]{CTL_KERN,KERN_ARGMAX},2, argmaxRef.getPointer(), size, NULL, _)!=0)
            throw new UnsupportedOperationException("Failed to get kernl.argmax: "+LIBC.strerror(Native.getLastError()));

        int argmax = argmaxRef.getValue();
        LOGGER.fine("argmax="+argmax);

        class StringArrayMemory extends Memory {
            private long offset=0;

            StringArrayMemory(long l) {
                super(l);
            }

            int readInt() {
                int r = getInt(offset);
                offset+=sizeOfInt;
                return r;
            }

            byte peek() {
                return getByte(offset);
            }

            String readString() {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte ch;
                while((ch = getByte(offset++))!='\0')
                    baos.write(ch);
                return baos.toString();
            }

            void skip0() {
                // skip trailing '\0's
                while(getByte(offset)=='\0')
                    offset++;
            }
        }
        StringArrayMemory m = new StringArrayMemory(argmax);
        size.setValue(argmax);
        if(LIBC.sysctl(new int[]{CTL_KERN,KERN_PROCARGS2,resolvePID(pid)},3, m, size, NULL, _)!=0)
            throw new UnsupportedOperationException("Failed to obtain ken.procargs2: "+LIBC.strerror(Native.getLastError()));

        
        /*
         * Make a sysctl() call to get the raw argument space of the
         * process.  The layout is documented in start.s, which is part
         * of the Csu project.  In summary, it looks like:
         *
         * /---------------\ 0x00000000
         * :               :
         * :               :
         * |---------------|
         * | argc          |
         * |---------------|
         * | arg[0]        |
         * |---------------|
         * :               :
         * :               :
         * |---------------|
         * | arg[argc - 1] |
         * |---------------|
         * | 0             |
         * |---------------|
         * | env[0]        |
         * |---------------|
         * :               :
         * :               :
         * |---------------|
         * | env[n]        |
         * |---------------|
         * | 0             |
         * |---------------| <-- Beginning of data returned by sysctl()
         * | exec_path     |     is here.
         * |:::::::::::::::|
         * |               |
         * | String area.  |
         * |               |
         * |---------------| <-- Top of stack.
         * :               :
         * :               :
         * \---------------/ 0xffffffff
         */

        JavaVMArguments args = new JavaVMArguments();
        int nargs = m.readInt();
        m.readString(); // exec path
        for( int i=0; i<nargs; i++) {
            m.skip0();
            args.add(m.readString());
        }

        // this is how you can read environment variables
//        List<String> lst = new ArrayList<String>();
//        while(m.peek()!=0)
//            lst.add(m.readString());

        return args;
    }

    private static final boolean IS_LITTLE_ENDIAN = "little".equals(System.getProperty("sun.cpu.endian"));

    private static final Logger LOGGER = Logger.getLogger(JavaVMArguments.class.getName());

    public static void main(String[] args) throws IOException {
        if (args.length==0)
            System.out.println(current());
        else {
            for (String arg : args) {
                System.out.println(of(Integer.valueOf(arg)));
            }
        }
    }
}
