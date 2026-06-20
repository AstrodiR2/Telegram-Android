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
    private static volatile String lastAiError = null;
    private static final int GROUP_CACHE_SIZE = 60;

    public static void addGroupMessage(long dialogId, String senderName, String senderUsername, String text) {
        groupMessageCache.computeIfAbsent(dialogId, k -> new java.util.LinkedList<>());
        java.util.LinkedList<String> cache = groupMessageCache.get(dialogId);
        String entry = senderName + (senderUsername != null && !senderUsername.isEmpty() ? " (@" + senderUsername + ")" : "") + ": " + text;
        cache.addLast(entry);
        if (cache.size() > GROUP_CACHE_SIZE) cache.removeFirst();
    }

    public static String getGroupHistory(long dialogId) {
        return getGroupHistory(dialogId, GROUP_CACHE_SIZE);
    }

    public static String getGroupHistory(long dialogId, int limit) {
        java.util.LinkedList<String> cache = groupMessageCache.get(dialogId);
        if (cache == null || cache.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        java.util.List<String> list = new java.util.ArrayList<>(cache);
        int start = Math.max(0, list.size() - limit);
        for (int i = start; i < list.size(); i++) {
            sb.append(list.get(i)).append("\n");
        }
        return sb.toString().trim();
    }
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
            case "/tts":
                handleTts(arg, dialogId, replyToMsg);
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
            CharSequence[] msg = {text};
            java.util.ArrayList<org.telegram.tgnet.TLRPC.MessageEntity> entities =
                org.telegram.messenger.MediaDataController.getInstance(UserConfig.selectedAccount).getEntities(msg, true);
            String cleanText = msg[0].toString();
            SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(cleanText, dialogId, replyToMsg, null, null, false, entities, null, null, false, 0, 0, null, false);
            SendMessagesHelper.getInstance(UserConfig.selectedAccount).sendMessage(params);
        });
    }

    private static long ttsCooldown = 0;

    private static void handleTts(String arg, long dialogId, MessageObject replyToMsg) {
        long now = System.currentTimeMillis();
        if (now - ttsCooldown < 10000) return;
        ttsCooldown = now;

        String text = arg.trim();
        if (text.isEmpty() && replyToMsg != null && replyToMsg.messageOwner != null) {
            text = replyToMsg.messageOwner.message;
        }
        if (text == null || text.isEmpty()) {
            sendLocal(dialogId, "❌ Формат: /tts [текст] или реплай на сообщение");
            return;
        }
        final String finalText = text;
        AiManager.textToSpeech(finalText, new AiManager.TtsCallback() {
            @Override
            public void onResult(java.io.File file) {
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        org.telegram.tgnet.TLRPC.TL_document doc = new org.telegram.tgnet.TLRPC.TL_document();
                        doc.mime_type = "audio/mpeg";
                        doc.file_name = "tts.mp3";
                        org.telegram.tgnet.TLRPC.TL_documentAttributeAudio audio = new org.telegram.tgnet.TLRPC.TL_documentAttributeAudio();
                        audio.voice = true;
                        doc.attributes.add(audio);
                        SendMessagesHelper.SendMessageParams p = SendMessagesHelper.SendMessageParams.of(doc, null, file.getAbsolutePath(), dialogId, replyToMsg, null, null, null, null, null, true, 0, 0, 0, null, null, false);
                        SendMessagesHelper.getInstance(UserConfig.selectedAccount).sendMessage(p);
                    } catch (Exception e) {
                        addLog("❌ TTS отправка: " + e.getMessage());
                    }
                });
            }
            @Override
            public void onError(String error) {
                addLog("❌ TTS: " + error);
                sendLocal(dialogId, "❌ " + error);
            }
        });
    }

    public static void sendAiResult(long dialogId, String result, MessageObject replyToMsg, int account) {
        // Проверяем есть ли блок кода
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("```(python|py|markdown|md)?\\n([\\s\\S]*?)```", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m = p.matcher(result);
        if (m.find()) {
            String lang = m.group(1) != null ? m.group(1).toLowerCase() : "";
            String code = m.group(2);
            String ext = (lang.equals("python") || lang.equals("py")) ? ".py" : ".md";
            String textBefore = result.substring(0, m.start()).trim();
            String textAfter = result.substring(m.end()).trim();
            String caption = (textBefore + " " + textAfter).trim();
            try {
                java.io.File dir = new java.io.File(android.os.Environment.getExternalStorageDirectory(), "Telegram/ai_files");
                dir.mkdirs();
                java.io.File file = new java.io.File(dir, "ai_" + System.currentTimeMillis() + ext);
                try (java.io.FileWriter fw = new java.io.FileWriter(file)) { fw.write(code); }
                final String finalCaption = caption.isEmpty() ? null : caption;
                final java.io.File finalFile = file;
                final String finalExt = ext;
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        org.telegram.tgnet.TLRPC.TL_document doc = new org.telegram.tgnet.TLRPC.TL_document();
                        doc.mime_type = finalExt.equals(".py") ? "text/x-python" : "text/markdown";
                        org.telegram.tgnet.TLRPC.TL_documentAttributeFilename attr = new org.telegram.tgnet.TLRPC.TL_documentAttributeFilename();
                        attr.file_name = "code" + finalExt;
                        doc.attributes.add(attr);
                        SendMessagesHelper.SendMessageParams fp = SendMessagesHelper.SendMessageParams.of(doc, null, finalFile.getAbsolutePath(), dialogId, replyToMsg, null, finalCaption, null, null, null, true, 0, 0, 0, null, null, false);
                        SendMessagesHelper.getInstance(account).sendMessage(fp);
                    } catch (Exception e) {
                        addLog("❌ Файл отправка: " + e.getMessage());
                        sendAiResult(dialogId, result, replyToMsg, UserConfig.selectedAccount);
                    }
                });
                return;
            } catch (Exception e) {
                addLog("❌ Файл создание: " + e.getMessage());
            }
        }
        // Обычный текст
        sendAiResult(dialogId, result, replyToMsg, UserConfig.selectedAccount);
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
        sb.append("❌ Last AI error: ").append(lastAiError != null ? lastAiError : "нет").append("\n");
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
                "📋 **Команды мода**\n" +
                "\n" +
                "👤 **Утилиты**\n" +
                "  `/id` — твой Telegram ID\n" +
                "  `/ping` — задержка\n" +
                "\n" +
                "🧮 **Инструменты**\n" +
                "  `/calc <выр>` — калькулятор\n" +
                "  `/dice` — кубик 🎲\n" +
                "  `/coin` — монетка 🪙\n" +
                "  `/8ball` — магический шар 🎱\n" +
                "  `/remind <сек> <текст>` — напоминание ⏰\n" +
                "\n" +
                "👻 **Режимы**\n" +
                "  `/invisible` — невидимка\n" +
                "  `/autoreply` — автоответчик\n" +
                "\n" +
                "🤖 **AI**\n" +
                "  `/ai <вопрос>` — спросить AI\n" +
                "  `/ai api` — настроить AI\n" +
                "  `/ai role` — сменить роль (0=Квас, 1=Assistant, 2=Summarizer, 3=Proofreader, 4=Квас-агент)\n" +
                "  `/ai clean` — очистить историю\n" +
                "  `/ai user` — авто-ответ на реплай/упоминание в этом чате\n" +
                "  `/ai user off` — выключить авто-ответ везде\n" +
                "  `/exit` — выйти из настройки\n" +
                "\n" +
                "  `/log` — состояние мода (диагностика)\n" +
                "  `/help` — эта справка";
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
        if (arg.trim().equals("clean mem")) {
            AiManager.clearLongMemory(ctx);
            AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ctx, "Долгая память очищена", Toast.LENGTH_SHORT).show());
            return;
        }
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
        String question = arg.trim();
        if (replyToMsg != null) {
            String replyText = replyToMsg.messageOwner != null ? replyToMsg.messageOwner.message : null;
            if (replyText != null && !replyText.isEmpty()) {
                question = "[Пользователь реплайнул на сообщение: \"" + replyText + "\"]\n" + question;
            }
        }
        AiManager.ask(ctx, dialogId, question, new AiManager.AiCallback() {
            @Override
            public void onResult(String result) {
                sendAiResult(dialogId, result, replyToMsg, UserConfig.selectedAccount);
            }
            @Override
            public void onError(String error) {
                lastAiError = error;
                addLog("❌ Ошибка AI: " + error);
                AndroidUtilities.runOnUIThread(() ->
                    Toast.makeText(ctx, "❌ Ошибка AI: " + error, Toast.LENGTH_LONG).show());
            }
        });
    }
}
