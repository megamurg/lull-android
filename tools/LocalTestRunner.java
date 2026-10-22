import java.lang.reflect.*;
import java.util.*;
/** Runs every public no-arg method annotated @org.junit.Test in the named classes. Exit code 1 on any failure. */
public class LocalTestRunner {
    public static void main(String[] args) throws Exception {
        int passed = 0, failed = 0;
        for (String name : args) {
            Class<?> cls = Class.forName(name);
            for (Method m : cls.getMethods()) {
                if (!m.isAnnotationPresent(org.junit.Test.class)) continue;
                Object instance = cls.getDeclaredConstructor().newInstance();
                try { m.invoke(instance); passed++; System.out.println("  ok   " + cls.getSimpleName() + "." + m.getName()); }
                catch (InvocationTargetException e) {
                    failed++; Throwable c = e.getCause();
                    System.out.println("  FAIL " + cls.getSimpleName() + "." + m.getName() + ": " + c);
                    StackTraceElement[] st = c.getStackTrace();
                    for (int i = 0; i < Math.min(4, st.length); i++) System.out.println("         at " + st[i]);
                }
            }
        }
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
