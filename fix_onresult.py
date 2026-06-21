filepath = "TMessagesProj/src/main/java/org/telegram/messenger/CommandHandler.java"
with open(filepath, "r") as f:
    content = f.read()

old = '''            @Override
            public void onResult(String result) {
                String q = finalQuestion.length() > 40 ? finalQuestion.substring(0, 40) + "..." : finalQuestion;
                String formatted = "───「 " + q + " 」───\\n" + result;
                sendAiResult(dialogId, formatted, replyToMsg, UserConfig.selectedAccount);
            }
            @Override
            public void onError(String error) {
                addLog("AI ERROR: " + error);
                AndroidUtilities.runOnUIThread(() ->
                    Toast.makeText(ctx, error, Toast.LENGTH_LONG).show());
            }'''

new = '''            @Override
            public void onResult(String result) {
                if (result == null) result = "(пустой ответ)";
                String q = finalQuestion.length() > 40 ? finalQuestion.substring(0, 40) + "..." : finalQuestion;
                String formatted = "───「 " + q + " 」───\\n" + result;
                sendAiResult(dialogId, formatted, replyToMsg, UserConfig.selectedAccount);
            }
            @Override
            public void onError(String error) {
                addLog("AI ERROR: " + error);
                if (error == null) error = "Неизвестная ошибка";
                sendLocal(dialogId, "❌ " + error);
            }'''

if old in content:
    content = content.replace(old, new, 1)
    with open(filepath, "w") as f:
        f.write(content)
    print("OK: onResult null guard + onError uses sendLocal")
else:
    print("ERROR: pattern not found")
