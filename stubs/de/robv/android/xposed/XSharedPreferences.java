package de.robv.android.xposed;
import java.io.File;
public final class XSharedPreferences {
  public XSharedPreferences(String pkg, String name) {}
  public void reload() {}
  public File getFile() { return null; }
  public boolean getBoolean(String k, boolean d) { return d; }
  public String getString(String k, String d) { return d; }
}
