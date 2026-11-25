package de.robv.android.xposed;
import java.util.Set;
public final class XposedBridge {
  public static Set hookAllMethods(Class<?> c, String n, XC_MethodHook h) { return null; }
  public static Set hookAllConstructors(Class<?> c, XC_MethodHook h) { return null; }
  public static void log(String s) {}
  public static void log(Throwable t) {}
}
