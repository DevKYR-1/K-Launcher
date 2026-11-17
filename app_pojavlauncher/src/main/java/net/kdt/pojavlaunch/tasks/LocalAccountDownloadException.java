package net.kdt.pojavlaunch.tasks;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;

import androidx.fragment.app.FragmentActivity;

import net.kdt.pojavlaunch.LauncherActivity;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.ShowErrorActivity;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.fragments.MicrosoftLoginFragment;
import net.kdt.pojavlaunch.lifecycle.ContextExecutorTask;

import java.io.IOException;

/**
 * A Local (offline) account tried to start a version that is not completely installed. Game files are only
 * downloaded for Microsoft accounts: a Local account has no licence that could be checked, and it must
 * not pretend to have one. A Local account can start versions that are already installed.
 * <p>
 * This is not a crash and not a network problem, so instead of an error with a stack trace
 * the user gets an explanation and a button that opens the Microsoft login.
 * The technical details (how many files, which file) are in the message, that goes to the launcher log.
 */
public class LocalAccountDownloadException extends IOException implements ContextExecutorTask {
    // Do not change. Android really hates when this value changes for some reason.
    private static final long serialVersionUID = 3159830284472310861L;

    private final long mFileCount;

    /**
     * @param fileCount how many files are missing or damaged
     * @param firstFile the name of the first of them, for the log
     * @param damaged true if the first file exists but did not pass the SHA-1 check, false if it is missing
     */
    public LocalAccountDownloadException(long fileCount, String firstFile, boolean damaged) {
        super("A Local account cannot download game files: " + fileCount + " file(s) are missing or damaged ("
                + firstFile + (damaged ? " is damaged" : " is missing") + ")");
        this.mFileCount = fileCount;
    }

    @Override
    public void executeWithActivity(Activity activity) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(R.string.dl_err_local_title);
        builder.setMessage(activity.getString(R.string.dl_err_local_account, mFileCount));
        builder.setPositiveButton(R.string.dl_err_local_login, (dialog, which) -> openMicrosoftLogin(activity));
        builder.setNegativeButton(android.R.string.cancel, null);
        ShowErrorActivity.installRemoteDialogHandling(activity, builder);
        builder.show();
    }

    private static void openMicrosoftLogin(Activity activity) {
        if(activity instanceof LauncherActivity) {
            Tools.swapFragment((FragmentActivity) activity, MicrosoftLoginFragment.class, MicrosoftLoginFragment.TAG, null);
        } else {
            // The dialog is shown on top of another screen (the launcher was left during the download):
            // bring the launcher back, the account can be added from there.
            Intent intent = new Intent(activity, LauncherActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            activity.startActivity(intent);
        }
    }

    @Override
    public void executeWithApplication(Context context) {
        // Never called: Tools.showErrorRemote notifies the user itself when there is no activity,
        // and the dialog is shown through executeWithActivity() when the notification is opened.
    }
}
