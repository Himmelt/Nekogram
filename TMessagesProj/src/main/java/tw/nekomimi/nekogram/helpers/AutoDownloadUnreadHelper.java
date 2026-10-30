package tw.nekomimi.nekogram.helpers;

import androidx.collection.LongSparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.DownloadController;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.StatsController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/**
 * Fetches unread messages of all dialogs (with their media) while the app is running,
 * when the "Auto-Download Unread Messages" option is enabled for the current network type.
 */
public class AutoDownloadUnreadHelper implements NotificationCenter.NotificationCenterDelegate {

    private static final AutoDownloadUnreadHelper[] Instance = new AutoDownloadUnreadHelper[UserConfig.MAX_ACCOUNT_COUNT];

    private static final int MAX_DIALOGS_PER_CHECK = 20;
    private static final int MAX_MESSAGES_PER_DIALOG = 100;
    private static final long MIN_CHECK_INTERVAL = 30_000;
    private static final int ALREADY_REQUESTED_FROM_SERVER = -1;

    private final int currentAccount;
    private final int classGuid;
    private final LongSparseArray<Long> checkedDialogs = new LongSparseArray<>();
    private final LongSparseArray<Integer> expectedUnread = new LongSparseArray<>();

    private boolean started;
    private boolean scheduled;
    private boolean retryPending;
    private boolean forceCheck;
    private long lastCheckTime;

    private AutoDownloadUnreadHelper(int account) {
        currentAccount = account;
        classGuid = ConnectionsManager.generateClassGuid();
    }

    public static AutoDownloadUnreadHelper getInstance(int num) {
        AutoDownloadUnreadHelper localInstance = Instance[num];
        if (localInstance == null) {
            synchronized (AutoDownloadUnreadHelper.class) {
                localInstance = Instance[num];
                if (localInstance == null) {
                    Instance[num] = localInstance = new AutoDownloadUnreadHelper(num);
                }
            }
        }
        localInstance.ensureStarted();
        return localInstance;
    }

    private void ensureStarted() {
        if (started) {
            return;
        }
        started = true;
        AndroidUtilities.runOnUIThread(() -> {
            NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.dialogsNeedReload);
            NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagesDidLoad);
            NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.fileLoadFailed);
            NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.didUpdateConnectionState);
            NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.screenStateChanged);
            requestCheck();
        });
    }

    public void onSettingsChanged() {
        AndroidUtilities.runOnUIThread(() -> {
            checkedDialogs.clear();
            expectedUnread.clear();
            forceCheck = true;
            requestCheck();
        });
    }

    private void requestCheck() {
        if (scheduled) {
            return;
        }
        scheduled = true;
        AndroidUtilities.runOnUIThread(this::check);
    }

    private void check() {
        scheduled = false;
        UserConfig config = UserConfig.getInstance(currentAccount);
        if (!config.isClientActivated() || !isEnabled()) {
            return;
        }
        MessagesController messagesController = MessagesController.getInstance(currentAccount);
        if (messagesController.getTotalDialogsCount() == 0) {
            scheduled = true;
            AndroidUtilities.runOnUIThread(this::check, 3000);
            return;
        }
        long now = System.currentTimeMillis();
        if (!forceCheck && now - lastCheckTime < MIN_CHECK_INTERVAL) {
            return;
        }
        forceCheck = false;
        lastCheckTime = now;

        LongSparseArray<TLRPC.Dialog> dialogs = messagesController.dialogs_dict;
        int requested = 0;
        boolean hasMore = false;
        for (int a = 0, N = dialogs.size(); a < N; a++) {
            TLRPC.Dialog dialog = dialogs.valueAt(a);
            if (dialog == null || dialog instanceof TLRPC.TL_dialogFolder || dialog.folder_id == 1 || dialog.unread_count <= 0) {
                continue;
            }
            if (DialogObject.isEncryptedDialog(dialog.id) || messagesController.isPromoDialog(dialog.id, false)) {
                continue;
            }
            long hash = ((long) dialog.top_message << 32) ^ dialog.unread_count;
            int index = checkedDialogs.indexOfKey(dialog.id);
            if (index >= 0 && checkedDialogs.valueAt(index) == hash) {
                continue;
            }
            if (requested >= MAX_DIALOGS_PER_CHECK) {
                hasMore = true;
                break;
            }
            checkedDialogs.put(dialog.id, hash);
            expectedUnread.put(dialog.id, Math.min(dialog.unread_count, MAX_MESSAGES_PER_DIALOG));
            // try the local cache first, the server is queried only if the cache does not contain all unread messages
            messagesController.loadMessages(dialog.id, 0, false, Math.min(dialog.unread_count, MAX_MESSAGES_PER_DIALOG), 0, 0, true, 0, classGuid, 0, 0, 0, 0, 0, 1, false);
            requested++;
        }
        if (hasMore) {
            scheduled = true;
            AndroidUtilities.runOnUIThread(this::check, MIN_CHECK_INTERVAL + 2000);
        }
    }

    private boolean isEnabled() {
        DownloadController downloadController = DownloadController.getInstance(currentAccount);
        int type = getCurrentNetworkType();
        return downloadController.isAutoDownloadUnreadEnabled(type) && downloadController.isPresetEnabled(type);
    }

    private int getCurrentNetworkType() {
        switch (ApplicationLoader.getAutodownloadNetworkType()) {
            case StatsController.TYPE_WIFI:
                return 1;
            case StatsController.TYPE_ROAMING:
                return 2;
            default:
                return 0;
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.dialogsNeedReload) {
            requestCheck();
        } else if (id == NotificationCenter.fileLoadFailed) {
            // 息屏或后台时 MTProto 网络会被挂起，导致自动下载的媒体失败并从队列中移除，这里标记待重试
            if ((Integer) args[1] == 0 && isEnabled()) {
                retryPending = true;
            }
        } else if (id == NotificationCenter.didUpdateConnectionState) {
            if (retryPending && isEnabled() && isNetworkConnected()) {
                retryFailedMedia();
            }
        } else if (id == NotificationCenter.screenStateChanged) {
            if (retryPending && ApplicationLoader.isScreenOn && isEnabled() && isNetworkConnected()) {
                retryFailedMedia();
            }
        } else if (id == NotificationCenter.messagesDidLoad) {
            if ((Integer) args[10] != classGuid) {
                return;
            }
            long dialogId = (Long) args[0];
            boolean isCache = (Boolean) args[3];
            @SuppressWarnings("unchecked")
            ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[2];
            processMessages(dialogId, isCache, messages);
        }
    }

    private void retryFailedMedia() {
        retryPending = false;
        checkedDialogs.clear();
        expectedUnread.clear();
        forceCheck = true;
        requestCheck();
    }

    private boolean isNetworkConnected() {
        int state = ConnectionsManager.getInstance(currentAccount).getConnectionState();
        return state == ConnectionsManager.ConnectionStateConnected || state == ConnectionsManager.ConnectionStateUpdating;
    }

    private void processMessages(long dialogId, boolean isCache, ArrayList<MessageObject> messages) {
        if (!isEnabled()) {
            return;
        }
        int found = downloadUnreadMedia(messages);
        if (!isCache) {
            expectedUnread.remove(dialogId);
            return;
        }
        Integer expected = expectedUnread.get(dialogId);
        if (expected == null) {
            return;
        }
        if (expected == ALREADY_REQUESTED_FROM_SERVER) {
            expectedUnread.remove(dialogId);
        } else if (found > 0 && found < expected) {
            // cached messages are not enough, request the remaining unread messages from the server
            expectedUnread.put(dialogId, ALREADY_REQUESTED_FROM_SERVER);
            MessagesController.getInstance(currentAccount).loadMessages(dialogId, 0, false, expected, 0, 0, false, 0, classGuid, 0, 0, 0, 0, 0, 1, false);
        } else {
            expectedUnread.remove(dialogId);
        }
    }

    private int downloadUnreadMedia(ArrayList<MessageObject> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        ArrayList<TLRPC.Message> toDownload = new ArrayList<>();
        ArrayList<MessageObject> toDownloadCovers = new ArrayList<>();
        int found = 0;
        for (int a = 0, N = messages.size(); a < N; a++) {
            MessageObject messageObject = messages.get(a);
            if (messageObject == null || messageObject.messageOwner == null) {
                continue;
            }
            if (!messageObject.isUnread() && !messageObject.isContentUnread()) {
                continue;
            }
            found++;
            if (messageObject.isHiddenSensitive()) {
                continue;
            }
            TLRPC.Message message = messageObject.messageOwner;
            if (message.media == null) {
                continue;
            }
            toDownload.add(message);
            if (messageObject.isVideo() || messageObject.isGif() || messageObject.isRoundVideo()) {
                toDownloadCovers.add(messageObject);
            }
        }
        if (!toDownload.isEmpty()) {
            MessagesStorage.getInstance(currentAccount).addMessageMediaToDownloadQueue(toDownload);
        }
        prefetchMediaCovers(toDownloadCovers);
        return found;
    }

    /**
     * 预取视频类消息的封面（document.thumbs），避免自动下载了视频后聊天界面
     * 仍需要单独向服务器请求封面。
     */
    private void prefetchMediaCovers(ArrayList<MessageObject> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        int side = AndroidUtilities.getPhotoSize();
        FileLoader fileLoader = FileLoader.getInstance(currentAccount);
        for (int a = 0, N = messages.size(); a < N; a++) {
            MessageObject messageObject = messages.get(a);
            if (messageObject.photoThumbs == null || messageObject.photoThumbs.isEmpty() || messageObject.photoThumbsObject == null) {
                continue;
            }
            TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(messageObject.photoThumbs, side);
            if (thumb == null) {
                continue;
            }
            ImageLocation location = ImageLocation.getForObject(thumb, messageObject.photoThumbsObject);
            if (location == null) {
                continue;
            }
            fileLoader.loadFile(location, null, null, FileLoader.PRIORITY_LOW, 1);
        }
    }
}
