package androidx.test.ext.junit.runners;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Build;
import android.os.StrictMode;
import android.support.v7.app.AppCompatDelegate;
import android.view.View;
import androidx.test.espresso.NoMatchingViewException;
import androidx.test.espresso.ViewAssertion;
import androidx.test.espresso.action.ViewActions;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.platform.io.PlatformTestStorage;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitor;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.test.services.storage.TestStorage;
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityValidator;
import com.google.android.apps.common.testing.accessibility.framework.integrations.internal.rules.CollectingListener;
import com.google.android.apps.common.testing.accessibility.framework.integrations.internal.rules.UncheckedIOException;
import com.google.android.apps.common.testing.accessibility.framework.integrations.reporting.ChipLinker;
import com.google.android.apps.common.testing.accessibility.framework.integrations.reporting.ProtoWriter;
import com.google.android.apps.common.testing.accessibility.framework.integrations.reporting.StepResult;
import com.google.android.apps.common.testing.accessibility.framework.integrations.reporting.TestCaseResult;
import com.google.common.collect.ImmutableList;
import com.google.testing.screendiffing.android.AndroidImageDiffer;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.runner.Description;
import org.junit.runner.Runner;

public class A11yPassiveScanner {

  private static final String TEST_SERVICES_PACKAGE_NAME =
      "com.google.android.apps.common.testing.services";

  /** Filename used to write the proto representation of ATF findings. */
  private static final String PROTO_FILENAME = "a11y.binarypb";

  private final ProtoWriter protoWriter = new ProtoWriter();
  private boolean assertionAdded = false;
  private final CollectingListener collectingListener = new CollectingListener();
  private final Logger logger = Logger.getLogger(A11yPassiveScanner.class.getSimpleName());
  private final AccessibilityValidator accessibilityValidator =
      new AccessibilityValidator()
          .enablePassiveScanning()
          .setRunChecksFromRootView(true)
          .addCheckListener(collectingListener);
  private PlatformTestStorage testStorage = null;
  private ChipLinker chipLinker = null;
  private boolean isRunningInEspressoTest = false;

  private final ViewAssertion accessibilityCheckAssertion =
      new ViewAssertion() {
        @Override
        public void check(View view, NoMatchingViewException noViewFoundException) {
          logger.log(Level.INFO, ">> check view");

          if (noViewFoundException != null) {
            logger.log(
                Level.WARNING,
                String.format(
                    "'accessibility checks could not be performed because view '%s' was not"
                        + "found.\n",
                    noViewFoundException.getViewMatcherDescription()));
            throw noViewFoundException;
          }

          // Is it possible that the view is null?
          // if (view == null) {
          //  logger.log(Level.INFO, ">> view is null");
          //  return;
          // }

          StrictMode.ThreadPolicy originalPolicy = StrictMode.allowThreadDiskWrites();
          try {
            accessibilityValidator.check(view);
            createTestStorageIfNeeded(view.getContext());
          } finally {
            StrictMode.setThreadPolicy(originalPolicy);
          }
        }
      };

  public A11yPassiveScanner() {}

  public void beforeTest(Runner delegate) {
    try {
      logger.log(Level.INFO, ">>> Build.FINGERPRINT = " + Build.FINGERPRINT);
    } catch (Throwable e) {
      logger.log(Level.INFO, ">>> Build.FINGERPRINT is not available");
    }

    try {
      setRunningInEspressoTest();
    } catch (Throwable e) {
      logger.log(Level.INFO, ">>> failed to set running in espresso test");
    }

    logger.log(Level.INFO, ">> isRunningInEspressoTest = " + isRunningInEspressoTest);
    logger.log(Level.INFO, ">> dislayName = " + delegate.getDescription().getDisplayName());
    logger.log(Level.INFO, ">> class name = " + delegate.getDescription().getClassName());
    logger.log(
        Level.INFO,
        ">> test class simple Name = " + delegate.getDescription().getTestClass().getSimpleName());

    boolean isInstrumentationRegistryAvailable = false;
    if (isRunningInEspressoTest) {
      try {
        Class.forName("androidx.test.platform.app.InstrumentationRegistry");
        isInstrumentationRegistryAvailable = true;
      } catch (Throwable e) {
        logger.log(Level.INFO, ">>> cound not find InstrumentationRegistry");
      }

      ContentResolver contentResolver = null;
      boolean isRunningGoogle3Test = false;
      if (isInstrumentationRegistryAvailable) {
        try {
          Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
          contentResolver = context.getContentResolver();

          isRunningGoogle3Test = isPackageInstalled(context, TEST_SERVICES_PACKAGE_NAME);
          logger.log(Level.INFO, ">> isGoogle3Test = " + isRunningGoogle3Test);

        } catch (RuntimeException e) {
          contentResolver = null;
          logger.log(Level.INFO, ">> Could not get content resolver");
        }

        if (!assertionAdded && isRunningGoogle3Test && (contentResolver != null)) {
          configA11yValidator();
          AndroidImageDiffer.ignoreImageDiffs = true;
          logger.log(Level.INFO, ">> Add a11y checks global assertion");

          try {
            ViewActions.addGlobalAssertion("A11y Checks", accessibilityCheckAssertion);
            assertionAdded = true;

            if (A11yPassiveScanConfig.enableNightMode) {
              enableNightMode();
            }

          } catch (Throwable e) {
            logger.log(Level.INFO, ">> Could not add global assertion");
          }
        }
      }
    }
  }

  public void afterTest(Runner delegate) {
    if (assertionAdded) {
      logger.log(Level.INFO, ">> Remove a11y checks global assertion");
      ViewActions.removeGlobalAssertion(accessibilityCheckAssertion);
      assertionAdded = false;

      if (A11yPassiveScanConfig.runA11yCheckAfterTest) {
        runAfterTestA11yCheck();
      }

      if (testStorage != null) {
        writeFindingsToProtoFile(PROTO_FILENAME, getTestIdentifier(delegate.getDescription()));
      } else {
        logger.log(Level.INFO, ">> test storage is null");
      }
    }
  }

  private void createTestStorageIfNeeded(Context context) {
    if (testStorage == null) {
      try {
        testStorage = new TestStorage(context.getApplicationContext().getContentResolver());
        chipLinker = new ChipLinker(testStorage);
      } catch (Throwable e) {
        logger.log(Level.INFO, ">> failed to create test storage");
      }
    }
  }

  private void configA11yValidator() {
    accessibilityValidator.setCaptureScreenshots(A11yPassiveScanConfig.captureScreenshots);
    accessibilityValidator.setSaveImages(A11yPassiveScanConfig.saveImages);
    accessibilityValidator.setThrowExceptionFor(A11yPassiveScanConfig.throwExcetpionFor);
  }

  private void runAfterTestA11yCheck() {
    // ActivityLifecycleMonitorRegistry can only be accessed from the main thread.
    InstrumentationRegistry.getInstrumentation().runOnMainSync(this::runAfterTestA11yCheckImpl);
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
  }

  private void runAfterTestA11yCheckImpl() {
    logger.log(Level.INFO, ">> Attempting post test a11y check.");
    try {
      ActivityLifecycleMonitor reg = ActivityLifecycleMonitorRegistry.getInstance();
      if (reg.getActivitiesInStage(Stage.RESUMED).isEmpty()) {
        logger.log(Level.WARNING, ">> No activities were in the resumed stage during final check!");
        return;
      }
      for (Activity act : reg.getActivitiesInStage(Stage.RESUMED)) {
        View view = act.getWindow().getDecorView().findViewById(android.R.id.content);
        if (view != null) {
          logger.log(Level.WARNING, ">> Triggered post test a11y checks");

          StrictMode.ThreadPolicy originalPolicy = StrictMode.allowThreadDiskWrites();
          try {
            accessibilityValidator.check(view);

            createTestStorageIfNeeded(view.getContext());

          } finally {
            StrictMode.setThreadPolicy(originalPolicy);
          }

        } else {
          logger.log(
              Level.WARNING,
              "No root view found in activity " + act.getClass().getName() + ". Checks skipped.");
        }
      }
    } catch (IllegalStateException e) {
      logger.log(
          Level.WARNING, ">> Unable to get ActivityLifecycleMonitorRegistry. Checks skipped.");
    } catch (Throwable t) {
      logger.log(Level.WARNING, ">> Encountered error on after() test a11y check. Checks skipped.");
    }
  }

  private static void enableNightMode() {
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES));
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
  }

  private String getTestIdentifier(Description description) {
    return String.format(
        Locale.ENGLISH,
        "%s.%s",
        description.getTestClass().getSimpleName(),
        description.getMethodName());
  }

  private void writeFindingsToProtoFile(String filename, String testCase) {
    ImmutableList<StepResult> stepResults = collectingListener.getStepResults();

    // Always write a file even if there are no results.

    try (OutputStream out = getTestOutputStream(filename)) {
      protoWriter.write(out, getTestCaseResult(stepResults, testCase));
    } catch (IOException e) {
      throw new UncheckedIOException(String.format("Failed to write proto file: %s ", filename), e);
    }

    if (chipLinker != null) {
      chipLinker.link(testCase);
    }
  }

  private TestCaseResult getTestCaseResult(ImmutableList<StepResult> stepResults, String testCase) {
    return new TestCaseResult(testCase, stepResults);
  }

  private OutputStream getTestOutputStream(String outputPath) throws IOException {
    return this.testStorage.openOutputFile(outputPath);
  }

  private void setRunningInEspressoTest() {
    try {
      Class.forName("androidx.test.espresso.Espresso");
      isRunningInEspressoTest = true;
    } catch (Throwable t) {
      isRunningInEspressoTest = false;
    }
  }

  private static boolean isPackageInstalled(Context context, String packageName) {
    try {
      context.getPackageManager().getPackageInfo(packageName, 0);
      return true;
    } catch (Throwable e) {
      return false;
    }
  }
}
