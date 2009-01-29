package com.sun.akuma;

import com.sun.jna.StringArray;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Arrays;
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

        String os = System.getProperty("os.name");
        if("Linux".equals(os))
            return currentLinux();
        if("SunOS".equals(os))
            return currentSolaris();

        throw new UnsupportedOperationException("Unsupported Operating System "+os);
    }

    private static JavaVMArguments currentLinux() throws IOException {
        int pid = LIBC.getpid();

        String cmdline = readFile(new File("/proc/" + pid + "/cmdline"));
        JavaVMArguments args = new JavaVMArguments(Arrays.asList(cmdline.split("\0")));

        // we don't want them inherited
        args.removeSystemProperty(Daemon.class.getName());
        args.removeSystemProperty(NetworkServer.class.getName()+".mode");
        return args;
    }

    private static JavaVMArguments currentSolaris() throws IOException {
        int pid = LIBC.getpid();
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

    private static final boolean IS_LITTLE_ENDIAN = "little".equals(System.getProperty("sun.cpu.endian"));

    private static final Logger LOGGER = Logger.getLogger(JavaVMArguments.class.getName());
}
