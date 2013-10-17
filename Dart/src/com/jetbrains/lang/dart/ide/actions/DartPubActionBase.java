package com.jetbrains.lang.dart.ide.actions;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.jetbrains.lang.dart.DartBundle;
import com.jetbrains.lang.dart.ide.settings.DartSettings;
import icons.DartIcons;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;

abstract public class DartPubActionBase extends AnAction {
  private static final Logger LOG = Logger.getInstance("#com.jetbrains.lang.dart.ide.actions.DartPubActionBase");
  private static final String GROUP_DISPLAY_ID = "Dart Pub Tool";

  public DartPubActionBase() {
    super(DartIcons.Dart_16);
  }

  @Override
  public void update(AnActionEvent e) {
    e.getPresentation().setText(getPresentableText());
    final boolean enabled = getModuleAndPubspecYamlFile(e) != null;
    e.getPresentation().setVisible(enabled);
    e.getPresentation().setEnabled(enabled);
  }

  @Nullable
  private Pair<Module, VirtualFile> getModuleAndPubspecYamlFile(final AnActionEvent e) {
    final Module module = LangDataKeys.MODULE.getData(e.getDataContext());
    final PsiFile psiFile = CommonDataKeys.PSI_FILE.getData(e.getDataContext());

    if (module != null && psiFile != null && psiFile.getName().equalsIgnoreCase("pubspec.yaml")) {
      final VirtualFile file = psiFile.getOriginalFile().getVirtualFile();
      return file != null && isEnabled(file) ? Pair.create(module, file) : null;
    }
    return null;
  }

  protected boolean isEnabled(@NotNull VirtualFile file) {
    return true;
  }

  @Nls
  protected abstract String getPresentableText();

  protected abstract String getPubCommandArgument();

  protected abstract String getSuccessOutputMessage();

  @Override
  public void actionPerformed(final AnActionEvent e) {
    final Pair<Module, VirtualFile> moduleAndPubspecYamlFile = getModuleAndPubspecYamlFile(e);
    if (moduleAndPubspecYamlFile == null) return;

    File sdkRoot = getSdkRoot(moduleAndPubspecYamlFile);
    if (sdkRoot == null) {
      final int answer = Messages.showDialog(moduleAndPubspecYamlFile.first.getProject(), "Dart SDK is not configured",
                                             getPresentableText(), new String[]{"Configure SDK", "Cancel"}, 0, Messages.getErrorIcon());
      if (answer != 0) return;

      ShowSettingsUtil.getInstance().showSettingsDialog(moduleAndPubspecYamlFile.first.getProject(), DartBundle.message("dart.title"));

      sdkRoot = getSdkRoot(moduleAndPubspecYamlFile);
      if (sdkRoot == null) return;
    }

    File pubFile = new File(sdkRoot, SystemInfo.isWindows ? "bin/pub.bat" : "bin/pub");
    if (!pubFile.isFile()) {
      final int answer =
        Messages.showDialog(moduleAndPubspecYamlFile.first.getProject(), DartBundle.message("dart.sdk.bad.dartpub.path", pubFile.getPath()),
                            getPresentableText(), new String[]{"Configure SDK", "Cancel"}, 0, Messages.getErrorIcon());
      if (answer != 0) return;

      ShowSettingsUtil.getInstance().showSettingsDialog(moduleAndPubspecYamlFile.first.getProject(), DartBundle.message("dart.title"));

      sdkRoot = getSdkRoot(moduleAndPubspecYamlFile);
      if (sdkRoot == null) return;

      pubFile = new File(sdkRoot, SystemInfo.isWindows ? "bin/pub.bat" : "bin/pub");
      if (!pubFile.isFile()) return;
    }

    doExecute(moduleAndPubspecYamlFile.first, moduleAndPubspecYamlFile.second, sdkRoot.getPath(), pubFile.getPath());
  }

  private void doExecute(final Module module, final VirtualFile pubspecYamlFile, final String sdkPath, final String pubPath) {
    final Task.Backgroundable task = new Task.Backgroundable(module.getProject(), getPresentableText(), true) {
      public void run(@NotNull ProgressIndicator indicator) {
        indicator.setText("Running pub manager...");
        indicator.setIndeterminate(true);
        final GeneralCommandLine command = new GeneralCommandLine();
        command.setExePath(pubPath);
        command.setWorkDirectory(pubspecYamlFile.getParent().getPath());
        command.addParameter(getPubCommandArgument());
        command.getEnvironment().put("DART_SDK", sdkPath);

        ApplicationManager.getApplication().invokeAndWait(new Runnable() {
          @Override
          public void run() {
            FileDocumentManager.getInstance().saveAllDocuments();
          }
        }, ModalityState.defaultModalityState());


        try {
          final ProcessOutput processOutput = new CapturingProcessHandler(command).runProcess();

          LOG.debug("pub terminated with exit code: " + processOutput.getExitCode());
          LOG.debug(processOutput.getStdout());
          LOG.debug(processOutput.getStderr());

          final String output = processOutput.getStdout().trim();
          if (output.contains(getSuccessOutputMessage())) {
            Notifications.Bus.notify(new Notification(GROUP_DISPLAY_ID, getPresentableText(),
                                                      getSuccessOutputMessage().replace('!', '.'),
                                                      NotificationType.INFORMATION));
            pubspecYamlFile.getParent().refresh(true, true);
          }
          else {
            // todo presentable output!
            Notifications.Bus.notify(new Notification(GROUP_DISPLAY_ID, getPresentableText(),
                                                      DartBundle.message("dart.pub.error", output, processOutput.getStderr()),
                                                      NotificationType.ERROR));
          }
        }
        catch (ExecutionException ex) {
          LOG.error(ex);
          Notifications.Bus.notify(new Notification(GROUP_DISPLAY_ID, getPresentableText(),
                                                    DartBundle.message("dart.pub.exception", ex.getMessage()),
                                                    NotificationType.ERROR));
        }
      }
    };

    task.queue();
  }

  @Nullable
  private static File getSdkRoot(final Pair<Module, VirtualFile> moduleAndPubspecYamlFile) {
    final DartSettings settings = DartSettings.getSettingsForModule(moduleAndPubspecYamlFile.first);
    final String sdkPath = settings == null ? null : settings.getSdkPath();
    final File sdkRoot = sdkPath == null || StringUtil.isEmptyOrSpaces(sdkPath) ? null : new File(sdkPath);
    return sdkRoot == null || !sdkRoot.isDirectory() ? null : sdkRoot;
  }
}
