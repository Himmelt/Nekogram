package tw.nekomimi.nekogram.helpers;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessageObject;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 记录本地的"已收藏"标记。收藏动作本身是把消息转发到 Saved Messages（我的收藏），
 * 这里只用于在消息旁边显示黄色的收藏按钮。
 */
public class MessageFavoritesHelper {

    private static final SharedPreferences preferences =
            ApplicationLoader.applicationContext.getSharedPreferences("nekofavorites", Context.MODE_PRIVATE);

    private static final Set<String> favorites = new HashSet<>(preferences.getStringSet("favorites", Collections.emptySet()));

    public static boolean isFavorite(int account, MessageObject message) {
        if (message == null || message.messageOwner == null) {
            return false;
        }
        return favorites.contains(getKey(account, message));
    }

    public static void setFavorite(int account, MessageObject message, boolean favorite) {
        if (message == null || message.messageOwner == null) {
            return;
        }
        final String key = getKey(account, message);
        final boolean changed;
        if (favorite) {
            changed = favorites.add(key);
        } else {
            changed = favorites.remove(key);
        }
        if (changed) {
            preferences.edit().putStringSet("favorites", new HashSet<>(favorites)).apply();
        }
    }

    private static String getKey(int account, MessageObject message) {
        return account + "_" + message.getDialogId() + "_" + message.getId();
    }
}
