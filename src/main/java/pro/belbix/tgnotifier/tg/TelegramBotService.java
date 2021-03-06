package pro.belbix.tgnotifier.tg;

import static com.pengrad.telegrambot.UpdatesListener.CONFIRMED_UPDATES_ALL;
import static pro.belbix.tgnotifier.tg.Commands.HELP;
import static pro.belbix.tgnotifier.tg.Commands.HELP_TEXT;
import static pro.belbix.tgnotifier.tg.Commands.INFO;
import static pro.belbix.tgnotifier.tg.Commands.UNKNOWN_COMMAND;
import static pro.belbix.tgnotifier.tg.Commands.WELCOME_MESSAGE;
import static pro.belbix.tgnotifier.tg.Commands.responseForCommand;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.Update;
import java.util.List;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import pro.belbix.tgnotifier.Properties;
import pro.belbix.tgnotifier.db.DbService;
import pro.belbix.tgnotifier.db.entity.UserEntity;
import pro.belbix.tgnotifier.models.DtoI;

@Log4j2
@Service
public class TelegramBotService {

    private final DbService dbService;
    private final Properties properties;
    private final DefaultMessageHandler defaultMessageHandler;
    private MessageSender messageSender;
    private final AddressesMessageHandler addressesMessageHandler = new AddressesMessageHandler();
    private final ImportantEventsHandler importantEventsHandler;

    public TelegramBotService(DbService dbService, Properties properties,
                              DefaultMessageHandler defaultMessageHandler,
                              ImportantEventsHandler importantEventsHandler){
        this.properties = properties;
        this.dbService = dbService;
        this.defaultMessageHandler = defaultMessageHandler;
        this.importantEventsHandler = importantEventsHandler;
    }

    public void init() {
        TelegramBot bot = new TelegramBot(properties.getTelegramToken());
        bot.setUpdatesListener(this::updatesListener);
        messageSender = new MessageSender(bot);
        log.info("Telegram Bot started");
    }

    private int updatesListener(List<Update> updates) {
        log.info("Get updates " + updates.size());
        try {
            for (Update u : updates) {
                if (u == null || u.message() == null || u.message().chat() == null || u.message().chat().id() == null) {
                    continue;
                }
                long chatId = u.message().chat().id();
                if (!dbService.isKnownChatId(chatId)) {
                    log.info("Chat added " + chatId);
                    sendMessage(chatId, WELCOME_MESSAGE);
                    saveNewUser(u.message());
                    continue;
                }
                handleMessage(u.message());
            }
        } catch (Exception e) {
            log.error("Update listener err", e);
        }
        return CONFIRMED_UPDATES_ALL;
    }

    public void sendMessage(long chatId, String message) {
        messageSender.send(chatId, message);
    }

    private void saveNewUser(Message m) {
        UserEntity userEntity = new UserEntity();
        userEntity.setId(m.chat().id());
        userEntity.setName(m.from().username());
        userEntity.setUserId(m.from().id());
        dbService.saveNewUser(userEntity);
    }

    private void handleMessage(Message m) {
        String text = m.text();
        long chatId = m.chat().id();
        log.info("Received message from user " + text);
        try {
            if (text.startsWith("/")) {
                handleCommand(m);
            } else {
                handleValue(m);
            }
        } catch (Exception e) {
            log.error("Error handle message " + text, e);
            sendMessage(chatId, "Error while handling your request, use correct syntax. " + HELP);
        }
    }

    private void handleCommand(Message m) {
        String text = m.text();
        long chatId = m.chat().id();
        if (text.startsWith(HELP)) {
            sendMessage(chatId, HELP_TEXT);
        } else if (text.startsWith(INFO)) {
            sendUserInfo(chatId);
        } else {
            String callback = responseForCommand(text);
            if (!UNKNOWN_COMMAND.equals(callback)) {
                dbService.updateLastCommand(chatId, text);
            }
            sendMessage(chatId, callback);
        }
    }

    private void handleValue(Message m) {
        String text = m.text();
        long chatId = m.chat().id();

        String result = dbService.updateValueForLastCommand(chatId, text) + ". " + HELP;
        log.info("Value updated with result " + result);
        sendMessage(chatId, result);
    }

    private void sendUserInfo(long chatId) {
        sendMessage(chatId, dbService.findById(chatId).print());
    }

    public void sendDto(DtoI dto) {
        if (dto == null) {
            return;
        }

        for (UserEntity user : dbService.findAllChats()) {
            try {
                CheckResult checkResult = defaultMessageHandler.checkAndUpdate(user, dto);
                if (checkResult.isSuccess()) {
                    sendMessage(user.getId(), checkResult.getMessage());
                }
                String ownerMsg = addressesMessageHandler.check(user, dto);
                if (ownerMsg != null) {
                    sendMessage(user.getId(), ownerMsg);
                }
                CheckResult eventResult = importantEventsHandler.checkAndUpdate(user, dto);
                if (eventResult != null && eventResult.isSuccess()) {
                    sendMessage(user.getId(), eventResult.getMessage());
                }
            } catch (Exception e) {
                log.error("Error while handle " + dto.print());
            }
        }
    }


}
