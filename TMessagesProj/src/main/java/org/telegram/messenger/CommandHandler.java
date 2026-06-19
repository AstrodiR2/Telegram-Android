package org.telegram.messenger;

import android.os.Handler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedList;
import org.telegram.tgnet.TLRPC;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SendMessagesHelper;
import android.widget.Toast;
import android.content.Context;
import android.os.Looper;
import org.telegram.messenger.AiManager;
import org.telegram.tgnet.ConnectionsManager;

public class CommandHandler {

    private static boolean invisibleMode = false;
    private static boolean autoReplyEnabled = false;
    private static String autoReplyMessage = null;
    private static boolean waitingForAutoReply = false;

    // /ai user feature
    private static final HashSet<Long> aiUserChats = new HashSet<>();
    private static final HashMap<Long, Long> aiUserCooldown = new HashMap<>();
    private static final HashMap<Long, java.util.LinkedList<String>> groupMessageCache = new HashMap<>();
    private static final int GROUP_CACHE_SIZE = 20;
    private static final HashMap<Long, LinkedList<Integer>> myMessageIdsCache = new HashMap<>();
    private static final int MAX_CACHED_MESSAGE_IDS = 100;

    // Event log buffer
    private static final LinkedList<String> eventLog = new LinkedList<>();
    private static final int MAX_LOG_ENTRIES = 30;
    public static void addLog(String entry) {
        String ts = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date());
        synchronized (eventLog) {
            eventLog.addLast("[" + ts + "] " + entry);
            if (eventLog.size() > MAX_LOG_ENTRIES) eventLog.removeFirst();
        }
    }
    private static final long AI_USER_COOLDOWN_MS = 10000;

    // AI wizard state
    public static final int AI_WIZARD_NONE = 0;
    public static final int AI_WIZARD_URL = 1;
    public static final int AI_WIZARD_MODEL = 2;
    public static final int AI_WIZARD_TOKEN = 3;
    private static int aiWizardStep = AI_WIZARD_NONE;
    private static String aiWizardUrl = "";
    private static String aiWizardModel = "";
    private static long aiWizardDialogId = 0;

    public static boolean handle(String text, long dialogId, MessageObject replyToMsg) {
        ensureMyMessageObserverRegistered();
        if (text == null || !text.startsWith("/")) return false;

        String[] parts = text.trim().split(" ", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1] : "";

        switch (cmd) {
            case "/id":
                handleId(dialogId);
                return true;
            case "/ping":
                handlePing(dialogId);
                return true;
            case "/calc":
                handleCalc(arg, dialogId);
                return true;
            case "/dice":
                handleDice(dialogId);
                return true;
            case "/coin":
                handleCoin(dialogId);
                return true;
            case "/8ball":
                handleEightBall(dialogId);
                return true;
            case "/invisible":
                handleInvisible(dialogId);
                return true;
            case "/help":
                handleHelp(dialogId);
                return true;
            case "/log":
                handleLog(dialogId);
                return true;
            case "/ghostping":
                return true;
            case "/autoreply":
                handleAutoReply(arg, dialogId);
                return true;
            case "/remind":
                handleRemind(arg, dialogId);
                return true;
            case "/ai":
                handleAi(arg, dialogId, replyToMsg);
                return true;
            case "/exit":
                handleExit(dialogId);
                return true;
            case "/qr":
            case "/chatstat":
            case "/topwords":
            case "/activity":
            case "/weather":
                return true;
            default:
                return false;
        }
    }

    private static void sendLocal(long dialogId, String text) {
        sendLocal(dialogId, text, null);
    }

    private static void sendLocal(long dialogId, String text, MessageObject replyToMsg) {
        AndroidUtilities.runOnUIThread(() -> {
            SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(text, dialogId, replyToMsg, null, null, false, null, null, null, false, 0, 0, null, false);
            SendMessagesHelper.getInstance(UserConfig.selectedAccount).sendMessage(params);
        });
    }

    private static void handleId(long dialogId) {
        long myId = UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId();
        sendLocal(dialogId, "🆔 Твой ID: " + myId);
    }

    private static void handlePing(long dialogId) {
        long start = System.currentTimeMillis();
        long ping = System.currentTimeMillis() - start;
        sendLocal(dialogId, "🏓 Пинг: " + ping + "ms");
    }

    private static void handleCalc(String expr, long dialogId) {
        try {
            double result = eval(expr);
            sendLocal(dialogId, result == (long)result ? String.valueOf((long)result) : String.valueOf(result));
        } catch (Exception e) {
            sendLocal(dialogId, "❌ Ошибка в выражении");
        }
    }

    private static void handleDice(long dialogId) {
        int result = (int)(Math.random() * 6) + 1;
        sendLocal(dialogId, "🎲 Выпало: " + result);
    }

    private static void handleCoin(long dialogId) {
        sendLocal(dialogId, Math.random() < 0.5 ? "🪙 Орёл" : "🪙 Решка");
    }

    private static void handleEightBall(long dialogId) {
        String[] answers = {
            "Да", "Нет", "Возможно", "Определённо нет",
            "Скорее всего", "Спроси позже", "Без сомнений"
        };
        String ans = answers[(int)(Math.random() * answers.length)];
        sendLocal(dialogId, "🎱 " + ans);
    }

    private static void handleInvisible(long dialogId) {
        invisibleMode = !invisibleMode;
        if (invisibleMode) {
            ConnectionsManager.getInstance(UserConfig.selectedAccount).setAppPaused(true, false);
            MessagesController.getInstance(UserConfig.selectedAccount).forceOffline();
        } else {
            ConnectionsManager.getInstance(UserConfig.selectedAccount).setAppPaused(false, false);
        }
        Toast.makeText(ApplicationLoader.applicationContext, invisibleMode
            ? "👻 Режим невидимки включён"
            : "👁 Режим невидимки выключён", Toast.LENGTH_SHORT).show();
    }

    private static void handleLog(long dialogId) {
        StringBuilder sb = new StringBuilder();
        sb.append("🛠 Состояние мода\n\n");
        sb.append("👻 Invisible: ").append(invisibleMode ? "ВКЛ" : "выкл").append("\n");
        sb.append("🤖 AutoReply: ").append(autoReplyEnabled ? "ВКЛ" : "выкл").append("\n");
        sb.append("⏳ Waiting for autoreply text: ").append(waitingForAutoReply).append("\n");
        sb.append("🧙 AI Wizard step: ").append(aiWizardStep).append("\n");
        sb.append("🤖 AI User enabled (this chat): ").append(aiUserChats.contains(dialogId)).append("\n");
        sb.append("🤖 AI User enabled chats count: ").append(aiUserChats.size()).append("\n");
        Long cd = aiUserCooldown.get(dialogId);
        if (cd != null) {
            long left = AI_USER_COOLDOWN_MS - (System.currentTimeMillis() - cd);
            sb.append("⏱ AI User cooldown left: ").append(Math.max(0, left)).append("ms\n");
        } else {
            sb.append("⏱ AI User cooldown: нет\n");
        }
        LinkedList<Integer> cached = myMessageIdsCache.get(dialogId);
        sb.append("📦 Cached my message IDs (this chat): ").append(cached != null ? cached.size() : 0).append("\n");
        sb.append("🤖 AI role: ").append(AiManager.getRoleName(AiManager.getCurrentRole(ApplicationLoader.applicationContext))).append("\n");
        java.util.LinkedList<String> gcache = groupMessageCache.get(dialogId);
        sb.append("💬 Group message cache (this chat): ").append(gcache != null ? gcache.size() : 0).append("/20\n");
        sb.append("\n📋 Лог событий:\n");
        synchronized (eventLog) {
            if (eventLog.isEmpty()) {
                sb.append("  (пусто)\n");
            } else {
                for (String entry : eventLog) {
                    sb.append("  ").append(entry).append("\n");
                }
            }
        }
        sendLocal(dialogId, sb.toString());
    }

    private static void handleHelp(long dialogId) {
        String help =
            "📋 Команды мода\n" +
            "\n" +
            "👤 Утилиты\n" +
            "  /id — твой Telegram ID\n" +
            "  /ping — задержка\n" +
            "\n" +
            "🧮 Инструменты\n" +
            "  /calc <выр> — калькулятор\n" +
            "  /dice — кубик 🎲\n" +
            "  /coin — монетка 🪙\n" +
            "  /8ball — магический шар 🎱\n" +
            "  /remind <сек> <текст> — напоминание ⏰\n" +
            "\n" +
            "👻 Режимы\n" +
            "  /invisible — невидимка\n" +
            "  /autoreply — автоответчик\n" +
            "\n" +
            "🤖 AI\n" +
            "  /ai <вопрос> — спросить AI\n" +
            "  /ai api — настроить AI\n" +
            "  /ai role — сменить роль (0=Квас, 1=Assistant, 2=Summarizer, 3=Proofreader, 4=Квас-агент)\n" +
            "  /ai clean — очистить историю\n" +
            "  /ai user — авто-ответ на реплай/упоминание в этом чате\n" +
            "  /ai user off — выключить авто-ответ везде\n" +
            "  /exit — выйти из настройки\n" +
            "\n" +
            "  /log — состояние мода (диагностика)\n" +
            "  /help — эта справка";
        sendLocal(dialogId, help);
    }

    private static void handleGhostPing(String arg, long dialogId) {
        String[] parts = arg.split(" ", 2);
        if (parts.length < 2) {
            AndroidUtilities.runOnUIThread(() -> Toast.makeText(ApplicationLoader.applicationContext, "❌ Формат: /ghostping <секунды> <сообщение>", Toast.LENGTH_SHORT).show());
            return;
        }
        try {
            int seconds = Integer.parseInt(parts[0]);
            String msg = parts[1];
            int[] sentMsgId = {0};
            SendMessagesHelper.getInstance(UserConfig.selectedAccount).sendMessage(
                SendMessagesHelper.SendMessageParams.of(msg, dialogId, null, null, null, false, null, null, null, false, 0, 0, null, false));
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                java.util.ArrayList<Integer> ids = new java.util.ArrayList<>();
                if (sentMsgId[0] != 0) ids.add(sentMsgId[0]);
                MessagesController.getInstance(UserConfig.selectedAccount).deleteMessages(
                    ids, null, null, dialogId, false, 0, false, 0L, null, 0);
            }, seconds * 1000L);
            AndroidUtilities.runOnUIThread(() -> Toast.makeText(ApplicationLoader.applicationContext, "👻 Сообщение удалится через " + seconds + "с", Toast.LENGTH_SHORT).show());
        } catch (NumberFormatException e) {
            AndroidUtilities.runOnUIThread(() -> Toast.makeText(ApplicationLoader.applicationContext, "❌ Укажи число секунд", Toast.LENGTH_SHORT).show());
        }
    }

    private static void handleAutoReply(String arg, long dialogId) {
        if (arg.equals("on")) {
            autoReplyEnabled = true;
            sendLocal(dialogId, "✅ Автоответчик включён");
        } else if (arg.equals("off")) {
            autoReplyEnabled = false;
            sendLocal(dialogId, "❌ Автоответчик выключён");
        } else if (arg.isEmpty()) {
            waitingForAutoReply = true;
            AndroidUtilities.runOnUIThread(() -> android.widget.Toast.makeText(ApplicationLoader.applicationContext, "💬 Напишите текст автоответчика:", android.widget.Toast.LENGTH_SHORT).show());
        } else {
            AndroidUtilities.runOnUIThread(() -> android.widget.Toast.makeText(ApplicationLoader.applicationContext, "❌ Формат: /autoreply on/off или просто /autoreply", android.widget.Toast.LENGTH_SHORT).show());
        }
    }

    private static void handleRemind(String arg, long dialogId) {
        String[] parts = arg.split(" ", 2);
        if (parts.length < 2) {
            sendLocal(dialogId, "❌ Формат: /remind <секунды> <текст>");
            return;
        }
        try {
            int seconds = Integer.parseInt(parts[0]);
            String msg = parts[1];
            new Handler(Looper.getMainLooper()).postDelayed(() ->
                sendLocal(dialogId, "⏰ Напоминание: " + msg), seconds * 1000L);
            sendLocal(dialogId, "✅ Напомню через " + seconds + "с");
        } catch (NumberFormatException e) {
            AndroidUtilities.runOnUIThread(() -> Toast.makeText(ApplicationLoader.applicationContext, "❌ Укажи число секунд", Toast.LENGTH_SHORT).show());
        }
    }

    // Простой eval для калькулятора
    private static double eval(String expr) {
        return new Object() {
            int pos = -1, ch;
            void nextChar() { ch = (++pos < expr.length()) ? expr.charAt(pos) : -1; }
            boolean eat(int c) {
                while (ch == ' ') nextChar();
                if (ch == c) { nextChar(); return true; }
                return false;
            }
            double parse() { nextChar(); double v = parseExpr(); if (pos < expr.length()) throw new RuntimeException(); return v; }
            double parseExpr() {
                double v = parseTerm();
                while (true) {
                    if (eat('+')) v += parseTerm();
                    else if (eat('-')) v -= parseTerm();
                    else return v;
                }
            }
            double parseTerm() {
                double v = parseFactor();
                while (true) {
                    if (eat('*')) v *= parseFactor();
                    else if (eat('/')) v /= parseFactor();
                    else return v;
                }
            }
            double parseFactor() {
                if (eat('+')) return parseFactor();
                if (eat('-')) return -parseFactor();
                double v;
                int start = pos;
                if (eat('(')) { v = parseExpr(); eat(')'); }
                else {
                    while ((ch >= '0' && ch <= '9') || ch == '.') nextChar();
                    v = Double.parseDouble(expr.substring(start, pos));
                }
                return v;
            }
        }.parse();
    }

    private static void handleAi(String arg, long dialogId, MessageObject replyToMsg) {
        android.content.Context ctx = ApplicationLoader.applicationContext;
        if (arg.trim().equals("api")) {
            aiWizardStep = AI_WIZARD_URL;
            aiWizardDialogId = dialogId;
            aiWizardUrl = "";
            aiWizardModel = "";
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, "Отправьте URL провайдера:\n(Например https://generativelanguage.googleapis.com/v1beta)", Toast.LENGTH_LONG).show());
            return;
        }
        if (arg.trim().equals("role")) {
            int newRole = AiManager.nextRole(ctx);
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, "Роль: " + AiManager.getRoleName(newRole), Toast.LENGTH_SHORT).show());
            return;
        }
        if (arg.trim().equals("clean")) {
            AiManager.clearHistory(ctx, dialogId);
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, "🧹 История очищена", Toast.LENGTH_SHORT).show());
            return;
        }
        if (arg.trim().equals("user")) {
            boolean nowOff = aiUserChats.contains(dialogId);
            if (nowOff) {
                aiUserChats.remove(dialogId);
            } else {
                aiUserChats.add(dialogId);
            }
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, nowOff ? "🤖 AI User выключен в этом чате" : "🤖 AI User включён в этом чате", Toast.LENGTH_SHORT).show());
            return;
        }
        if (arg.trim().equals("user off")) {
            aiUserChats.clear();
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, "🤖 AI User выключен везде", Toast.LENGTH_SHORT).show());
            return;
        }
        // /ai <вопрос>
        if (arg.trim().isEmpty()) {
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, "❌ Формат: /ai <вопрос>", Toast.LENGTH_SHORT).show());
            return;
        }
        if (!AiManager.isConfigured(ctx)) {
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, "❌ AI не настроен. Используй /ai api", Toast.LENGTH_SHORT).show());
            return;
        }
        AiManager.ask(ctx, dialogId, arg.trim(), new AiManager.AiCallback() {
            @Override
            public void onResult(String result) {
                sendLocal(dialogId, result, replyToMsg);
            }
            @Override
            public void onError(String error) {
                AndroidUtilities.runOnUIThread(() ->
                    Toast.makeText(ctx, error, Toast.LENGTH_LONG).show());
            }
        });
    }

    private static void handleExit(long dialogId) {
        android.content.Context ctx = ApplicationLoader.applicationContext;
        if (aiWizardStep != AI_WIZARD_NONE) {
            aiWizardStep = AI_WIZARD_NONE;
            aiWizardUrl = "";
            aiWizardModel = "";
            aiWizardDialogId = 0;
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, "✅ Вышел из режима настройки AI", Toast.LENGTH_SHORT).show());
        } else {
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, "❌ Ты не в режиме настройки", Toast.LENGTH_SHORT).show());
        }
    }

    // Вызывается из SendMessagesHelper для перехвата wizard шагов
    public static boolean handleAiWizardStep(String text, long dialogId) {
        if (aiWizardStep == AI_WIZARD_NONE) return false;
        if (text.startsWith("/")) return false;
        android.content.Context ctx = ApplicationLoader.applicationContext;
        if (aiWizardStep == AI_WIZARD_URL) {
            aiWizardUrl = text.trim();
            aiWizardStep = AI_WIZARD_MODEL;
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, "Модель ИИ:\n(Например gemini-2.5-flash)", Toast.LENGTH_LONG).show());
            return true;
        }
        if (aiWizardStep == AI_WIZARD_MODEL) {
            aiWizardModel = text.trim();
            aiWizardStep = AI_WIZARD_TOKEN;
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, "Свой API ключ:", Toast.LENGTH_LONG).show());
            return true;
        }
        if (aiWizardStep == AI_WIZARD_TOKEN) {
            String token = text.trim();
            AiManager.saveSettings(ctx, aiWizardUrl, aiWizardModel, token);
            aiWizardStep = AI_WIZARD_NONE;
            aiWizardUrl = "";
            aiWizardModel = "";
            aiWizardDialogId = 0;
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, "✅ AI настроен успешно!", Toast.LENGTH_SHORT).show());
            return true;
        }
        return false;
    }

    public static int getAiWizardStep() { return aiWizardStep; }

    public static boolean isInvisibleMode() { return invisibleMode; }
    public static boolean isAutoReplyEnabled() { return autoReplyEnabled; }
    public static String getAutoReplyMessage() { return autoReplyMessage; }
    public static void setAutoReplyMessage(String msg) { autoReplyMessage = msg; }
    public static boolean isWaitingForAutoReply() { return waitingForAutoReply; }
    public static void setWaitingForAutoReply(boolean v) { waitingForAutoReply = v; }

    // /ai user feature accessors
    public static void addGroupMessage(long dialogId, String senderName, String senderUsername, String text) {
        if (text == null || text.isEmpty()) return;
        String entry = senderName + (senderUsername != null && !senderUsername.isEmpty() ? " (@" + senderUsername + ")" : "") + ": " + text;
        java.util.LinkedList<String> cache = groupMessageCache.get(dialogId);
        if (cache == null) {
            cache = new java.util.LinkedList<>();
            groupMessageCache.put(dialogId, cache);
        }
        cache.addLast(entry);
        if (cache.size() > GROUP_CACHE_SIZE) cache.removeFirst();
    }

    public static String getGroupHistory(long dialogId) {
        java.util.LinkedList<String> cache = groupMessageCache.get(dialogId);
        if (cache == null || cache.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String entry : cache) sb.append(entry).append("\n");
        return sb.toString().trim();
    }

    public static boolean isAiUserEnabled(long dialogId) {
        return aiUserChats.contains(dialogId);
    }

    public static void cacheMyMessageId(long dialogId, int messageId) {
        LinkedList<Integer> list = myMessageIdsCache.get(dialogId);
        if (list == null) {
            list = new LinkedList<>();
            myMessageIdsCache.put(dialogId, list);
        }
        list.addLast(messageId);
        while (list.size() > MAX_CACHED_MESSAGE_IDS) {
            list.removeFirst();
        }
    }

    public static boolean isMyMessageId(long dialogId, int messageId) {
        LinkedList<Integer> list = myMessageIdsCache.get(dialogId);
        return list != null && list.contains(messageId);
    }

    public static boolean canAiUserReply(long dialogId) {
        Long last = aiUserCooldown.get(dialogId);
        if (last == null) return true;
        return System.currentTimeMillis() - last >= AI_USER_COOLDOWN_MS;
    }

    public static long getCooldownLeft(long dialogId) {
        Long last = aiUserCooldown.get(dialogId);
        if (last == null) return 0;
        return Math.max(0, AI_USER_COOLDOWN_MS - (System.currentTimeMillis() - last));
    }

    public static void markAiUserReplied(long dialogId) {
        aiUserCooldown.put(dialogId, System.currentTimeMillis());
    }

    private static boolean myMessageObserverRegistered = false;

    public static void ensureMyMessageObserverRegistered() {
        if (myMessageObserverRegistered) return;
        myMessageObserverRegistered = true;
        NotificationCenter.getInstance(UserConfig.selectedAccount).addObserver(new NotificationCenter.NotificationCenterDelegate() {
            @Override
            public void didReceivedNotification(int id, int account, Object... args) {
                if (id == NotificationCenter.messageReceivedByServer) {
                    try {
                        Integer newMsgId = (Integer) args[1];
                        Long did = (Long) args[3];
                        if (newMsgId != null && did != null) {
                            cacheMyMessageId(did, newMsgId);
                        }
                    } catch (Exception ignored) {}
                }
            }
        }, NotificationCenter.messageReceivedByServer);
    }
}
