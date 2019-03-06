package de.thesystems.cloudnet.discord;
/*
 * Created by Mc_Ruben on 26.02.2019
 */

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import de.thesystems.cloudnet.discord.json.SimpleJsonObject;
import lombok.Getter;
import net.dv8tion.jda.core.AccountType;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.JDABuilder;
import net.dv8tion.jda.core.entities.Game;
import net.dv8tion.jda.core.entities.TextChannel;
import net.dv8tion.jda.core.events.ReadyEvent;
import net.dv8tion.jda.core.hooks.ListenerAdapter;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public abstract class CloudNetDiscordBot<LogEntry> {

    private static final SimpleJsonObject DEFAULT_CONFIG = new SimpleJsonObject()
            .append("bot",
                    new SimpleJsonObject()
                            .append("token", "your api token which you can get from https://discordapp.com/developers/applications")
                            .append("consoleChannelIds", Collections.singleton(123456789))
                            .append("delay_between_queue_polls_ms", 750)
            )
            .append("presence",
                    new SimpleJsonObject()
                            .append("type", Game.GameType.DEFAULT)
                            .append("text", "with CloudNet v3 by Dytanic")
            )
            .append("permissions",
                    new SimpleJsonObject()
                            .append("useWhitelist", false)
                            .append("useBlacklist", false)
                            .append("whitelistedUsers", Collections.singleton(123456789))
                            .append("blacklistedUsers", Collections.singleton(123456789))
                            .append("users",
                                    newMapByKeys(Arrays.asList("123456789", "*", "name_of_some_group"), s -> new DiscordPermissionUser(s, Arrays.asList("*", "-cloudnet.command.reload")))
                            )
            );

    private static final Path CONFIG_PATH = Paths.get("modules/CloudNet-Console-Bot/config.json");

    @Getter
    private JDA jda;
    private String currentToken;

    @Getter
    private DiscordPermissionProvider permissionProvider = new DiscordPermissionProvider();
    @Getter
    private DiscordConsoleProvider provider = new DiscordConsoleProvider(this);
    @Getter
    private boolean shutdown = false;

    private SimpleJsonObject config;

    private static <K, V> Map<K, V> newMapByKeys(Collection<K> keys, Function<K, V> valueFunction) {
        Map<K, V> map = new HashMap<>(keys.size());
        for (K key : keys) {
            map.put(key, valueFunction.apply(key));
        }
        return map;
    }

    protected abstract void addLogHandler();

    protected abstract void handleLogInput(LogEntry logEntry);

    protected abstract void removeLogHandler();

    protected abstract Collection<LogEntry> getCachedLogEntries();

    public abstract DiscordCommandInfo getCommandFromLine(String line);

    public abstract void dispatchCommand(DiscordCommandSender sender, String line);

    public void enableBot() {
        this.reloadDiscordConfig();

        new Thread(this.provider).start();

        this.addLogHandler();

        this.getCachedLogEntries().forEach(this::handleLogInput);
    }

    public void disableBot() {
        this.shutdown = true;
        this.removeLogHandler();
        if (this.provider.getThread() != null) {
            this.provider.getThread().interrupt();
        }
        if (this.jda != null) {
            this.jda.shutdownNow();
            this.jda = null;
        }

    }

    public void clearChannel(TextChannel channel, Consumer<TextChannel> updatedChannelConsumer) {
        long oldId = channel.getIdLong();
        channel.createCopy().queue(newChannel -> {
            newChannel.getGuild().getController().modifyTextChannelPositions().selectPosition(channel).queue(aVoid -> {
                channel.delete().queue(aVoid1 -> {
                    this.provider.getChannels().set(this.provider.getChannels().indexOf(oldId), newChannel.getIdLong());

                    JsonArray jsonArray = this.getConfig().getJsonObject("bot").get("consoleChannelIds").getAsJsonArray();
                    int index = 0;
                    for (JsonElement element : jsonArray) {
                        if (element.getAsLong() == oldId) {
                            jsonArray.set(index, new JsonPrimitive(newChannel.getIdLong()));
                            break;
                        }
                        index++;
                    }
                    this.saveConfig();

                    updatedChannelConsumer.accept((TextChannel) newChannel);
                });
            });
        });
    }

    private void onBotStart(JDA jda) {
        jda.addEventListener(new UserInputListener(this));
    }

    public boolean reloadDiscordConfig() {
        {
            String token = this.loadConfigEntry("bot", "token").getString("token");
            if (!token.contains(" ")) {
                if (this.jda == null || this.currentToken == null || !this.currentToken.equals(token)) {
                    this.currentToken = token;
                    if (this.jda != null) {
                        this.jda.shutdownNow();
                    }
                    try {
                        JDABuilder builder = new JDABuilder(AccountType.BOT);
                        builder.setToken(token);
                        builder.setAutoReconnect(true);
                        builder
                                .addEventListener(new ListenerAdapter() {
                                    @Override
                                    public void onReady(ReadyEvent event) {
                                        onBotStart(event.getJDA());
                                    }
                                });
                        this.jda = builder.build();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            } else {
                System.err.println("Please provide the DiscordBot Token and the channelId for your console channel in the config.json in your DiscordBotModule!");
                return false;
            }
        }
        this.permissionProvider.setWhitelist(this.loadConfigEntry("permissions", "useWhitelist").getBoolean("useWhitelist"));
        this.permissionProvider.setBlacklist(this.loadConfigEntry("permissions", "useBlacklist").getBoolean("useBlacklist"));
        this.permissionProvider.setWhitelistedUsers(asList(this.loadConfigEntry("permissions", "whitelistedUsers").getJsonArray("whitelistedUsers")));
        this.permissionProvider.setBlacklistedUsers(asList(this.loadConfigEntry("permissions", "blacklistedUsers").getJsonArray("blacklistedUsers")));
        this.permissionProvider.setPermissionUsers(
                this.loadConfigEntry("permissions", "users").getJsonObject("users").entrySet().stream()
                        .map(entry -> SimpleJsonObject.GSON.get().fromJson(entry.getValue(), DiscordPermissionUser.class).setId(entry.getKey()))
                        .collect(Collectors.toList())
        );

        this.provider.setDelay(this.loadConfigEntry("bot", "delay_between_queue_polls_ms").getLong("delay_between_queue_polls_ms"));
        this.provider.setChannels(asList(this.loadConfigEntry("bot", "consoleChannelIds").getJsonArray("consoleChannelIds")));

        {
            try {
                Game game = Game.of(Game.GameType.valueOf(this.loadConfigEntry("presence", "type").getString("type")), this.loadConfigEntry("presence", "text").getString("text"));
                if (this.jda != null) {
                    this.jda.getPresence().setGame(game);
                }
            } catch (Exception e) {
                System.err.println("An invalid GameType is provided in the config.json of the DiscordBot Module, available types: "+ Arrays.stream(Game.GameType.values()).map(Enum::toString).collect(Collectors.joining(", ")));
            }
        }
        return true;
    }

    private List<Long> asList(JsonArray jsonArray) {
        return StreamSupport.stream(jsonArray.spliterator(), false).map(jsonElement -> SimpleJsonObject.GSON.get().fromJson(jsonElement, Long.class)).filter(Objects::nonNull).collect(Collectors.toList());
    }

    private SimpleJsonObject loadConfigEntry(String parent, String key) {
        SimpleJsonObject parentObj = this.getConfig().getDocument(parent);
        if (parentObj == null || !parentObj.contains(key)) {
            this.writeDefaults(this.getConfig().asJsonObject(), true, DEFAULT_CONFIG.asJsonObject());
            this.saveConfig();
            return this.getConfig().getDocument(parent);
        }
        return parentObj;
    }

    private SimpleJsonObject getConfig() {
        if (config == null)
            config = SimpleJsonObject.load(CONFIG_PATH);
        return config;
    }

    private void saveConfig() {
        getConfig().saveAsFile(CONFIG_PATH);
    }

    private void writeDefaults(JsonObject output, boolean firstLayer, JsonObject parent) {
        for (Map.Entry<String, JsonElement> entry : parent.entrySet()) {
            if (!output.has(entry.getKey()) || (entry.getValue().isJsonObject() && firstLayer)) {
                if (entry.getValue().isJsonObject()) {
                    if (firstLayer && output.has(entry.getKey())) {
                        JsonObject jsonObject = null;
                        if (output.has(entry.getKey())) {
                            JsonElement element = output.get(entry.getKey());
                            if (element.isJsonObject()) {
                                jsonObject = element.getAsJsonObject();
                            }
                        }
                        if (jsonObject == null) {
                            output = new JsonObject();
                        }
                        writeDefaults(jsonObject, false, entry.getValue().getAsJsonObject());
                        output.add(entry.getKey(), jsonObject);
                    } else {
                        output.add(entry.getKey(), entry.getValue());
                    }
                } else {
                    output.add(entry.getKey(), entry.getValue());
                }
            }
        }
    }

}