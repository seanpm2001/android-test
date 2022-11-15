package androidx.test.ext.junit.runners;

import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType;

public class A11yPassiveScanConfig {

  private A11yPassiveScanConfig() {}

  public static boolean saveImages = true;

  public static boolean captureScreenshots = true;

  public static AccessibilityCheckResultType throwExcetpionFor = null;

  public static boolean enableNightMode = false;

  public static boolean runA11yCheckAfterTest = false;
}
