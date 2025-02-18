/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.test.core.app.testing;

import static org.junit.Assert.assertFalse;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import androidx.annotation.Nullable;
import com.google.common.util.concurrent.SettableFuture;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** An Activity that records lifecycle states. */
public class RecordingActivity extends Activity {

  protected final List<String> callbacks = new ArrayList<>();
  private final Map<String, SettableFuture<String>> pendingFutures = new HashMap<>();

  private class VisibilityRecordingView extends View {

    public VisibilityRecordingView(Context context) {
      super(context);
    }

    // use onAttachedToWindow as a proxy for activity becoming visible
    @Override
    protected void onAttachedToWindow() {
      super.onAttachedToWindow();
      onEvent("onAttachedToWindow");
    }
  }

  public void listenForEvent(SettableFuture<String> future, String stateDescription) {
    if (callbacks.contains(stateDescription)) {
      future.set(stateDescription);
    } else {
      assertFalse(pendingFutures.containsKey(stateDescription));
      pendingFutures.put(stateDescription, future);
    }
  }

  protected void onEvent(String newStateDescription) {
    callbacks.add(newStateDescription);
    SettableFuture<String> future = pendingFutures.remove(newStateDescription);
    if (future != null) {
      future.set(newStateDescription);
    }
  }

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    setContentView(new VisibilityRecordingView(this));
    onEvent("onCreate");
  }

  @Override
  public void onStart() {
    super.onStart();
    onEvent("onStart");
    }

  @Override
  public void onPostCreate(Bundle savedInstanceState) {
    super.onPostCreate(savedInstanceState);
    onEvent("onPostCreate");
  }

  @Override
  public void onResume() {
    super.onResume();
    onEvent("onResume");
  }

  @Override
  public void onPostResume() {
    super.onPostResume();
    onEvent("onPostResume");
  }

  @Override
  public void onPause() {
    super.onPause();
    onEvent("onPause");
  }

  @Override
  public void onStop() {
    super.onStop();
    onEvent("onStop");
  }

  @Override
  public void onRestart() {
    super.onRestart();
    onEvent("onRestart");
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    onEvent("onDestroy");
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    onEvent("onWindowFocusChanged " + hasFocus);
  }

  public List<String> getCallbacks() {
    return callbacks;
  }
}
