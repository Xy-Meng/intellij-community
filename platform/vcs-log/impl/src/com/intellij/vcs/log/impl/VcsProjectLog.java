/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.vcs.log.impl;

import com.intellij.ide.caches.CachesInvalidator;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ApplicationNamesInfo;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsRoot;
import com.intellij.openapi.vcs.ui.VcsBalloonProblemNotifier;
import com.intellij.util.messages.MessageBus;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.messages.Topic;
import com.intellij.vcs.log.data.VcsLogData;
import com.intellij.vcs.log.ui.VcsLogPanel;
import com.intellij.vcs.log.ui.VcsLogUiImpl;
import org.jetbrains.annotations.CalledInAny;
import org.jetbrains.annotations.CalledInAwt;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;

import static com.intellij.vcs.log.util.PersistentUtil.LOG_CACHE;

public class VcsProjectLog {
  private static final Logger LOG = Logger.getInstance(VcsProjectLog.class);
  public static final Topic<ProjectLogListener> VCS_PROJECT_LOG_CHANGED =
    Topic.create("Project Vcs Log Created or Disposed", ProjectLogListener.class);
  private static final int RECREATE_LOG_TRIES = 5;
  @NotNull private final Project myProject;
  @NotNull private final MessageBus myMessageBus;
  @NotNull private final VcsLogTabsProperties myUiProperties;

  @NotNull
  private final LazyVcsLogManager myLogManager = new LazyVcsLogManager();
  private final AtomicInteger myRecreatedLogCount = new AtomicInteger();
  private volatile VcsLogUiImpl myUi;

  public VcsProjectLog(@NotNull Project project, @NotNull VcsLogTabsProperties uiProperties) {
    myProject = project;
    myMessageBus = project.getMessageBus();
    myUiProperties = uiProperties;
  }

  @Nullable
  public VcsLogData getDataManager() {
    VcsLogManager cached = myLogManager.getCached();
    if (cached == null) return null;
    return cached.getDataManager();
  }

  @NotNull
  private Collection<VcsRoot> getVcsRoots() {
    return Arrays.asList(ProjectLevelVcsManager.getInstance(myProject).getAllVcsRoots());
  }

  @NotNull
  public JComponent initMainLog(@NotNull String contentTabName) {
    myUi = myLogManager.getValue().createLogUi(VcsLogTabsProperties.MAIN_LOG_ID, contentTabName);
    return new VcsLogPanel(myLogManager.getValue(), myUi);
  }

  /**
   * The instance of the {@link VcsLogUiImpl} or null if the log was not initialized yet.
   */
  @Nullable
  public VcsLogUiImpl getMainLogUi() {
    return myUi;
  }

  @Nullable
  public VcsLogManager getLogManager() {
    return myLogManager.getCached();
  }

  @CalledInAny
  private void recreateLog() {
    ApplicationManager.getApplication().invokeLater(() -> {
      disposeLog();

      if (hasDvcsRoots()) {
        createLog();
      }
    });
  }

  @CalledInAwt
  private void recreateOnError(@NotNull Throwable t) {
    int recreated = myRecreatedLogCount.incrementAndGet();
    if (recreated > RECREATE_LOG_TRIES) {
      myRecreatedLogCount.set(0);

      String message = "VCS Log was recreated " +
                       recreated +
                       " times due to data corruption\n" +
                       "Delete " +
                       LOG_CACHE +
                       " directory and restart " +
                       ApplicationNamesInfo.getInstance().getFullProductName() +
                       " if this happens often.\n" +
                       t.getMessage();
      LOG.error(message, t);

      VcsLogManager manager = getLogManager();
      if (manager != null && manager.isLogVisible()) {
        VcsBalloonProblemNotifier.showOverChangesView(myProject, message, MessageType.ERROR);
      }
    }
    else {
      LOG.debug(t);
    }

    recreateLog();
  }

  @CalledInAwt
  private void disposeLog() {
    myUi = null;
    myLogManager.drop();
  }

  @CalledInAwt
  public void createLog() {
    VcsLogManager logManager = myLogManager.getValue();

    if (logManager.isLogVisible()) {
      logManager.scheduleInitialization();
    }
    else if (PostponableLogRefresher.keepUpToDate()) {
      VcsLogCachesInvalidator invalidator = CachesInvalidator.EP_NAME.findExtension(VcsLogCachesInvalidator.class);
      if (invalidator.isValid()) {
        HeavyAwareExecutor.executeOutOfHeavyProcessLater(logManager::scheduleInitialization, 5000);
      }
    }
  }

  private boolean hasDvcsRoots() {
    return !VcsLogManager.findLogProviders(getVcsRoots(), myProject).isEmpty();
  }

  public static VcsProjectLog getInstance(@NotNull Project project) {
    return ServiceManager.getService(project, VcsProjectLog.class);
  }

  private class LazyVcsLogManager {
    @Nullable private VcsLogManager myValue;

    @NotNull
    @CalledInAwt
    public synchronized VcsLogManager getValue() {
      if (myValue == null) {
        myValue = compute();
        myMessageBus.syncPublisher(VCS_PROJECT_LOG_CHANGED).logCreated();
      }
      return myValue;
    }

    @NotNull
    @CalledInAwt
    protected synchronized VcsLogManager compute() {
      return new VcsLogManager(myProject, myUiProperties, getVcsRoots(), false, VcsProjectLog.this::recreateOnError);
    }

    @CalledInAwt
    public synchronized void drop() {
      if (myValue != null) {
        myMessageBus.syncPublisher(VCS_PROJECT_LOG_CHANGED).logDisposed();
        Disposer.dispose(myValue);
      }
      myValue = null;
    }

    @Nullable
    public synchronized VcsLogManager getCached() {
      return myValue;
    }
  }

  public static class InitLogStartupActivity implements StartupActivity {
    @Override
    public void runActivity(@NotNull Project project) {
      if (ApplicationManager.getApplication().isUnitTestMode() || ApplicationManager.getApplication().isHeadlessEnvironment()) return;

      VcsProjectLog projectLog = getInstance(project);

      MessageBusConnection connection = project.getMessageBus().connect(project);
      connection.subscribe(ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED, projectLog::recreateLog);
      if (projectLog.hasDvcsRoots()) {
        ApplicationManager.getApplication().invokeLater(projectLog::createLog);
      }
    }
  }

  public interface ProjectLogListener {
    @CalledInAwt
    void logCreated();

    @CalledInAwt
    void logDisposed();
  }
}
