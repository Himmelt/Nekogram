package tw.nekomimi.nekogram.helpers;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.SparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * 记录"已收藏"标记。收藏动作本身是把消息转发到 Saved Messages（我的收藏），
 * 这里用于在消息旁边显示收藏按钮的状态（已收藏为实心黄色）。
 * <p>
 * 收藏副本自带源消息映射（{@code saved_peer_id} / {@code fwd_from.saved_from_peer}
 * + {@code fwd_from.saved_from_msg_id}），因此当收藏消息被加载时会把对应的源消息
 * 标记为已收藏；在收藏夹里删除副本后，会按副本 id 反查并清除标记。
 */
public class MessageFavoritesHelper implements NotificationCenter.NotificationCenterDelegate {

    private static final SharedPreferences preferences =
            ApplicationLoader.applicationContext.getSharedPreferences("nekofavorites", Context.MODE_PRIVATE);

    private static final Set<String> favorites = new HashSet<>(preferences.getStringSet("favorites", Collections.emptySet()));

    /** 收藏副本 id -> 源消息 key，仅内存索引，随收藏消息加载重建 */
    private static final HashMap<String, String> savedCopies = new HashMap<>();

    private static SparseArray<MessageFavoritesHelper> instances;

    private final int currentAccount;
    private boolean started;

    private MessageFavoritesHelper(int account) {
        currentAccount = account;
    }

    private static synchronized MessageFavoritesHelper getInstance(int account) {
        if (instances == null) {
            instances = new SparseArray<>();
        }
        MessageFavoritesHelper helper = instances.get(account);
        if (helper == null) {
            helper = new MessageFavoritesHelper(account);
            instances.put(account, helper);
        }
        return helper;
    }

    public static boolean isFavorite(int account, MessageObject message) {
        if (message == null || message.messageOwner == null) {
            return false;
        }
        getInstance(account).ensureStarted();
        synchronized (favorites) {
            return favorites.contains(getKey(account, message.getDialogId(), message.getId()));
        }
    }

    public static void setFavorite(int account, MessageObject message, boolean favorite) {
        if (message == null || message.messageOwner == null) {
            return;
        }
        if (addKey(getKey(account, message.getDialogId(), message.getId()), favorite)) {
            persist();
        }
    }

    private void ensureStarted() {
        if (started) {
            return;
        }
        started = true;
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagesDidLoad);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagesDeleted);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.messagesDidLoad) {
            final long dialogId = ((Number) args[0]).longValue();
            if (dialogId != UserConfig.getInstance(account).getClientUserId()) {
                return;
            }
            @SuppressWarnings("unchecked")
            final ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[2];
            if (messages == null || messages.isEmpty()) {
                return;
            }
            boolean changed = false;
            for (int a = 0, N = messages.size(); a < N; a++) {
                changed |= indexSavedMessage(messages.get(a));
            }
            if (changed) {
                persist();
                notifyChanged();
            }
        } else if (id == NotificationCenter.messagesDeleted) {
            // channelId == 0 表示删除的是 Saved Messages 本身的消息
            final long channelId = args[1] instanceof Number ? ((Number) args[1]).longValue() : 0;
            if (channelId != 0) {
                return;
            }
            @SuppressWarnings("unchecked")
            final ArrayList<Integer> ids = (ArrayList<Integer>) args[0];
            if (ids == null || ids.isEmpty()) {
                return;
            }
            boolean changed = false;
            for (int a = 0, N = ids.size(); a < N; a++) {
                changed |= removeSavedCopy(ids.get(a));
            }
            if (changed) {
                persist();
                notifyChanged();
            }
        }
    }

    private boolean indexSavedMessage(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return false;
        }
        final TLRPC.Message message = messageObject.messageOwner;
        if (message.fwd_from == null) {
            return false;
        }
        long sourceDialogId = 0;
        if (message.saved_peer_id != null) {
            sourceDialogId = DialogObject.getPeerDialogId(message.saved_peer_id);
        } else if (message.fwd_from.saved_from_peer != null) {
            sourceDialogId = DialogObject.getPeerDialogId(message.fwd_from.saved_from_peer);
        }
        if (sourceDialogId == 0) {
            return false;
        }
        final int sourceMessageId = message.fwd_from.saved_from_msg_id;
        if (sourceMessageId == 0) {
            return false;
        }
        final String sourceKey = getKey(currentAccount, sourceDialogId, sourceMessageId);
        synchronized (savedCopies) {
            savedCopies.put(currentAccount + "_" + message.id, sourceKey);
        }
        return addKey(sourceKey, true);
    }

    private boolean removeSavedCopy(int copyMessageId) {
        final String sourceKey;
        boolean hasOtherCopy;
        synchronized (savedCopies) {
            sourceKey = savedCopies.remove(currentAccount + "_" + copyMessageId);
            hasOtherCopy = sourceKey != null && savedCopies.containsValue(sourceKey);
        }
        if (sourceKey == null || hasOtherCopy) {
            // 没有索引或同一源消息还有其它收藏副本，保持已收藏状态
            return false;
        }
        return addKey(sourceKey, false);
    }

    private void notifyChanged() {
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_FAVORITES));
    }

    private static boolean addKey(String key, boolean favorite) {
        synchronized (favorites) {
            return favorite ? favorites.add(key) : favorites.remove(key);
        }
    }

    private static void persist() {
        final Set<String> snapshot;
        synchronized (favorites) {
            snapshot = new HashSet<>(favorites);
        }
        preferences.edit().putStringSet("favorites", snapshot).apply();
    }

    private static String getKey(int account, long dialogId, int messageId) {
        return account + "_" + dialogId + "_" + messageId;
    }
}
