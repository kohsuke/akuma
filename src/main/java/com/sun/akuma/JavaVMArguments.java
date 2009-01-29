package com.sun.akuma;

import com.sun.jna.StringArray;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Arrays;
import java.io.IOException;
import java.io.FileInputStream;
import java.io.File;
import java.io.ByteArrayOutputStream;

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
        int pid = LIBC.getpid();

        String cmdline = readFile(new File("/proc/" + pid + "/cmdline"));
        JavaVMArguments args = new JavaVMArguments(Arrays.asList(cmdline.split("\0")));

        // we don't want them inherited
        args.removeSystemProperty(Daemon.class.getName());
        args.removeSystemProperty(NetworkServer.class.getName()+".mode");
        return args;
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
}
