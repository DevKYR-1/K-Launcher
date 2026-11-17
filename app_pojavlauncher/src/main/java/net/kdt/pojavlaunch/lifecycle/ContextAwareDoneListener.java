package net.kdt.pojavlaunch.lifecycle;

import static net.kdt.pojavlaunch.MainActivity.INTENT_MINECRAFT_VERSION;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import net.kdt.pojavlaunch.MainActivity;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.lifecycle.ContextExecutor;
import net.kdt.pojavlaunch.lifecycle.ContextExecutorTask;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.tasks.AsyncMinecraftDownloader;
import net.kdt.pojavlaunch.utils.DownloadErrors;
import net.kdt.pojavlaunch.utils.NotificationUtils;

public class ContextAwareDoneListener implements AsyncMinecraftDownloader.DoneListener, ContextExecutorTask {
    // The application context: unlike an activity, it can be kept without leaking anything
    private final Context mApplicationContext;
    private final String mNormalizedVersionid;

    public ContextAwareDoneListener(Context baseContext, String versionId) {
        this.mApplicationContext = baseContext.getApplicationContext();
        this.mNormalizedVersionid = versionId;
    }

    private Intent createGameStartIntent(Context context) {
        Intent mainIntent = new Intent(context, MainActivity.class);
        mainIntent.putExtra(INTENT_MINECRAFT_VERSION, mNormalizedVersionid);
        mainIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return mainIntent;
    }

    @Override
    public void onDownloadDone() {
        ProgressKeeper.waitUntilDone(()->ContextExecutor.execute(this));
    }

    @Override
    public void onDownloadFailed(Throwable throwable) {
        // The dialog shows a message that says what happened and what to do (no internet, file not
        // found, verification failed...) instead of a stack trace. The stack trace is behind "Show more",
        // and the launcher log has it as well. A failure that has a dialog of its own (a Local account that
        // needs a Microsoft login) shows that one.
        Tools.showErrorRemote(DownloadErrors.describe(mApplicationContext, throwable), throwable);
    }

    @Override
    public void executeWithActivity(Activity activity) {
        try {
            Intent gameStartIntent = createGameStartIntent(activity);
            activity.startActivity(gameStartIntent);
            activity.finish();
            android.os.Process.killProcess(android.os.Process.myPid()); //You should kill yourself, NOW!
        } catch (Throwable e) {
            Tools.showError(activity.getBaseContext(), e);
        }
    }

    @Override
    public void executeWithApplication(Context context) {
        Intent gameStartIntent = createGameStartIntent(context);
        // Since the game is a separate process anyway, it does not matter if it gets invoked
        // from somewhere other than the launcher activity.
        // The only problem may arise if the launcher starts doing something when the user starts the notification.
        // So, the notification is automatically removed once there are tasks ongoing in the ProgressKeeper
        NotificationUtils.sendBasicNotification(context,
                R.string.notif_download_finished,
                R.string.notif_download_finished_desc,
                gameStartIntent,
                NotificationUtils.PENDINGINTENT_CODE_GAME_START,
                NotificationUtils.NOTIFICATION_ID_GAME_START
        );
        // You should keep yourself safe, NOW!
        // otherwise android does weird things...
    }
}
