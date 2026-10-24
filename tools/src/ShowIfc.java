import org.objectweb.asm.*;
import java.io.*;
import java.util.jar.*;
public class ShowIfc {
    public static void main(String[] a) throws Exception {
        JarInputStream j = new JarInputStream(new FileInputStream(a[0]));
        JarEntry e; String want = a[1];
        while ((e = j.getNextJarEntry()) != null) {
            if (!e.getName().equals(want)) continue;
            ClassReader cr = new ClassReader(j);
            String[] ifcs = cr.getInterfaces();
            System.out.print("implements:");
            for (String i : ifcs) System.out.print(" " + i);
            System.out.println("  super=" + cr.getSuperName());
            return;
        }
        System.out.println("not found: " + want);
    }
}