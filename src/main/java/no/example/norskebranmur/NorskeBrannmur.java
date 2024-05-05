package no.example.norskebranmur;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.io.IOException;  // Import IOException

public class NorskeBrannmur extends JavaPlugin implements Listener {
    private static final Logger LOGGER = Logger.getLogger(NorskeBrannmur.class.getName());
    private MongoClient mongoClient;
    private MongoDatabase database;
    private MongoCollection<Document> playersCollection;
    private FileConfiguration config;
    private Set<String> bannedIPs;
    private Set<UUID> bannedPlayers;
    private Map<UUID, Integer> playerMovementViolations;
    private Map<UUID, Integer> playerOtherViolations;
    private int maxMovementViolations;
    private int maxOtherViolations;
    private boolean checkMovement;
    private boolean checkOtherActivity;
    private String playerBlockMessage;
    private String playerWarningMessage;
    private boolean notifyAdminsBlockPlayer;
    private boolean notifyAdminsBlockIP;
    private List<String> blockedIPs;
    private List<Integer> blockedPorts;
    private List<String> blockedCommands;
    private List<String> whitelistedIPs;
    private boolean enableIPWhitelist;
    private boolean enableCommandWhitelist;
    private List<String> whitelistedCommands;
    private Map<UUID, Long> lastCommandTime;
    private long commandCooldown;
    private Map<UUID, Integer> playerCommandViolations;
    private int maxCommandViolations;
    private Map<UUID, Long> lastMovementTime;
    private long movementCooldown;
    private Map<UUID, Integer> playerMovementSpeedViolations;
    private int maxMovementSpeedViolations;
    private Map<UUID, Long> lastPacketTime = new ConcurrentHashMap<>();
    private long packetCooldown;
    private int maxPacketViolations;
    private int maxPacketsPerIP;

    // Additional method to detect DDoS attacks
    private boolean isDDoSAttack(Player player) {
        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();

        // Logg tidspunktet for den siste pakken mottatt fra denne spilleren
        Long lastPacketTime = this.lastPacketTime.get(playerId);

        // Hvis dette er den første pakken, bare logg tiden og returner false
        if (lastPacketTime == null) {
            this.lastPacketTime.put(playerId, currentTime);
            return false;
        }

        // Beregn tiden mellom denne og den siste pakken
        long timeDifference = currentTime - lastPacketTime;

        // Oppdater siste tidspunkt
        this.lastPacketTime.put(playerId, currentTime);

        // Tell antall pakker mottatt i denne tidsperioden
        Integer packetCount = playerOtherViolations.getOrDefault(playerId, 0) + 1;
        playerOtherViolations.put(playerId, packetCount);

        // Sjekk om antallet pakker overskrider en definert grense i en veldig kort tidsramme
        if (timeDifference < packetCooldown && packetCount > maxPacketsPerIP) {
            LOGGER.warning("DDoS-angrep mulig: Spiller " + player.getName() + " sendte " + packetCount + " pakker innen " + timeDifference + "ms");
            return true;
        }

        // Reset pakketelling etter et visst tidsrom
        if (timeDifference > packetCooldown) {
            playerOtherViolations.put(playerId, 0);
        }

        return false;
    }


    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = getConfig();
        loadConfig();
        String mongoUri = config.getString("mongo-uri", "your_mongo_uri_here");
        mongoClient = MongoClients.create(mongoUri);
        database = mongoClient.getDatabase("NorskeBrannmur");
        playersCollection = database.getCollection("Spillere");

        bannedIPs = ConcurrentHashMap.newKeySet();
        playerMovementViolations = new ConcurrentHashMap<>();
        playerOtherViolations = new ConcurrentHashMap<>();
        lastMovementTime = new ConcurrentHashMap<>();
        lastCommandTime = new ConcurrentHashMap<>();
        playerMovementSpeedViolations = new ConcurrentHashMap<>();

        Bukkit.getPluginManager().registerEvents(this, this);
        LOGGER.info("NorskeBrannmur plugin activated!");
    }


    @Override
    public void onDisable() {
        if (mongoClient != null) {
            mongoClient.close();
        }
        LOGGER.info("NorskeBrannmur plugin deactivated.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("brannmur")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Denne kommandoen kan bare brukes av spillere.");
                return true;
            }
            Player player = (Player) sender;
            if (!player.hasPermission("norskebranmur.use")) {
                player.sendMessage(ChatColor.RED + "Du har ikke tillatelse til å bruke denne kommandoen.");
                return true;
            }
            // Behandle subkommandoer for brannmuren her
            return true;  // Sørg for å returnere true når kommandoen er håndtert
        } else if (command.getName().equalsIgnoreCase("nbalert")) {
            // Håndterer sending av varslinger til admins
            sendAlert(sender, args);
            return true;
        } else if (command.getName().equalsIgnoreCase("spiller")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Denne kommandoen kan bare brukes av spillere.");
                return true;
            }
            Player player = (Player) sender;
            if (!player.hasPermission("norskebranmur.admin")) {
                player.sendMessage(ChatColor.RED + "Du har ikke tillatelse til å legge til spillere i databasen.");
                return true;
            }
            if (args.length == 1) {
                addPlayerToDatabase(args[0], player);
            } else {
                player.sendMessage(ChatColor.RED + "Bruk: /spiller <navn>");
            }
            return true;
        }
        return false;  // Returnerer false hvis ingen av kommandoene ble gjenkjent
    }


    private void addPlayerToDatabase(String playerName, Player admin) {
        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null) {
            admin.sendMessage(ChatColor.RED + "Spilleren " + playerName + " er ikke online eller eksisterer ikke.");
            return;
        }
        Document newPlayer = new Document("uuid", target.getUniqueId().toString())
                .append("name", target.getName())
                .append("ip", target.getAddress().getAddress().getHostAddress());
        playersCollection.insertOne(newPlayer);
        admin.sendMessage(ChatColor.GREEN + "Spilleren " + playerName + " er lagt til i databasen.");
    }




    private void banPlayerOrIP(Player player, String target) {
        if (Bukkit.getPlayerExact(target) != null) {
            Player targetPlayer = Bukkit.getPlayerExact(target);
            bannedPlayers.add(targetPlayer.getUniqueId());
            targetPlayer.kickPlayer(playerBlockMessage);
            if (notifyAdminsBlockPlayer) {
                Bukkit.broadcast(ChatColor.RED + "Spilleren " + targetPlayer.getName() + " er blokkert av brannmuren.", "norskebranmur.notify");
            }
            player.sendMessage(ChatColor.GREEN + "Spilleren " + target + " er nå blokkert av brannmuren.");
        } else {
            try {
                InetAddress address = InetAddress.getByName(target);
                String ipAddress = address.getHostAddress();
                bannedIPs.add(ipAddress);
                if (notifyAdminsBlockIP) {
                    Bukkit.broadcast(ChatColor.RED + "IP-adressen " + ipAddress + " er blokkert av brannmuren.", "norskebranmur.notify");
                }
                player.sendMessage(ChatColor.GREEN + "IP-adressen " + ipAddress + " er nå blokkert av brannmuren.");
            } catch (UnknownHostException e) {
                player.sendMessage(ChatColor.RED + "Ugyldig IP-adresse eller spillernavn.");
            }
        }
    }

    private void unbanPlayerOrIP(Player player, String target) {
        if (Bukkit.getPlayerExact(target) != null) {
            Player targetPlayer = Bukkit.getPlayerExact(target);
            if (bannedPlayers.remove(targetPlayer.getUniqueId())) {
                player.sendMessage(ChatColor.GREEN + "Spilleren " + target + " er nå fjernet fra brannmurens blokkeringsliste.");
            } else {
                player.sendMessage(ChatColor.RED + "Spilleren " + target + " var ikke blokkert av brannmuren.");
            }
        } else {
            try {
                InetAddress address = InetAddress.getByName(target);
                String ipAddress = address.getHostAddress();
                if (bannedIPs.remove(ipAddress)) {
                    player.sendMessage(ChatColor.GREEN + "IP-adressen " + ipAddress + " er nå fjernet fra brannmurens blokkeringsliste.");
                } else {
                    player.sendMessage(ChatColor.RED + "IP-adressen " + ipAddress + " var ikke blokkert av brannmuren.");
                }
            } catch (UnknownHostException e) {
                player.sendMessage(ChatColor.RED + "Ugyldig IP-adresse eller spillernavn.");
            }
        }
    }

    private void setMaxViolations(Player player, String newValue) {
        try {
            int newMax = Integer.parseInt(newValue);
            if (newMax > 0) {
                maxMovementViolations = newMax;
                maxOtherViolations = newMax;
                config.set("max-violations", newMax);
                config.set("anti-cheat.max-movement-violations", newMax);
                config.set("anti-cheat.max-other-violations", newMax);
                saveConfig();
                player.sendMessage(ChatColor.GREEN + "Maks antall tillatte brudd er nå satt til " + newMax + ".");
            } else {
                player.sendMessage(ChatColor.RED + "Ugyldig verdi for maks antall tillatte brudd.");
            }
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "Ugyldig tallformat. Vennligst oppgi et gyldig tall.");
        }
    }

    private void toggleWhitelist(Player player, String type) {
        if (type.equalsIgnoreCase("ip")) {
            enableIPWhitelist = !enableIPWhitelist;
            config.set("whitelist.enable-ip-whitelist", enableIPWhitelist);
            saveConfig();
            player.sendMessage(ChatColor.GREEN + "IP-hvitelisting er nå " + (enableIPWhitelist ? "aktivert" : "deaktivert") + ".");
        } else if (type.equalsIgnoreCase("command")) {
            enableCommandWhitelist = !enableCommandWhitelist;
            config.set("whitelist.enable-command-whitelist", enableCommandWhitelist);
            saveConfig();
            player.sendMessage(ChatColor.GREEN + "Kommando-hvitelisting er nå " + (enableCommandWhitelist ? "aktivert" : "deaktivert") + ".");
        } else {
            player.sendMessage(ChatColor.RED + "Ugyldig argument. Bruk /brannmur whitelist ip eller /brannmur whitelist command");
        }
    }

    private void sendAlert(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Denne kommandoen kan bare brukes av spillere.");
            return;
        }
        Player player = (Player) sender;
        if (!player.hasPermission("norskebranmur.notify")) {
            player.sendMessage(ChatColor.RED + "Du har ikke tillatelse til å sende varsler.");
            return;
        }
        if (args.length < 1) {
            player.sendMessage(ChatColor.RED + "Bruk: /nbalert <melding>");
            return;
        }
        String message = String.join(" ", args);
        Bukkit.broadcast(ChatColor.RED + "[NorskeBrannmur] " + ChatColor.RESET + message, "norskebranmur.notify");
        player.sendMessage(ChatColor.GREEN + "Varslingen ble sendt til alle administratorer.");
    }

    // Event handlers

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (bannedPlayers.contains(player.getUniqueId())) {
            event.setJoinMessage(null);
            player.kickPlayer(playerBlockMessage);
            LOGGER.info("Blocked banned player " + player.getName() + " from joining.");
            return;
        }
        if (enableIPWhitelist && !whitelistedIPs.contains(player.getAddress().getAddress().getHostAddress())) {
            LOGGER.info("Blocked non-whitelisted player " + player.getName() + " from joining.");
            event.setJoinMessage(null);
            player.kickPlayer(playerBlockMessage);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        playerMovementViolations.remove(player.getUniqueId());
        playerOtherViolations.remove(player.getUniqueId());
        lastCommandTime.remove(player.getUniqueId());
        playerCommandViolations.remove(player.getUniqueId());
        lastMovementTime.remove(player.getUniqueId());
        playerMovementSpeedViolations.remove(player.getUniqueId());
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (blockedCommands.contains(event.getBlockPlaced().getType().name().toLowerCase())) {
            event.setCancelled(true);
            player.sendMessage(playerWarningMessage);
            if (notifyAdminsBlockPlayer) {
                Bukkit.broadcast(ChatColor.RED + "Spilleren " + player.getName() + " forsøkte å plassere en blokk som er blokkert av brannmuren.", "norskebranmur.notify");
            }
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (blockedCommands.contains(event.getBlock().getType().name().toLowerCase())) {
            event.setCancelled(true);
            player.sendMessage(playerWarningMessage);
            if (notifyAdminsBlockPlayer) {
                Bukkit.broadcast(ChatColor.RED + "Spilleren " + player.getName() + " forsøkte å ødelegge en blokk som er blokkert av brannmuren.", "norskebranmur.notify");
            }
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!checkMovement) return;
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        if (whitelistedIPs.contains(player.getAddress().getAddress().getHostAddress())) {
            return;
        }
        long currentTime = System.currentTimeMillis();
        lastMovementTime.put(playerId, currentTime);

        int violations = playerMovementViolations.getOrDefault(playerId, 0);
        if (currentTime - lastMovementTime.getOrDefault(playerId, 0L) < movementCooldown) {
            playerMovementViolations.put(playerId, violations + 1);
        }
        if (violations >= maxMovementViolations) {
            player.kickPlayer(playerBlockMessage);
            if (notifyAdminsBlockPlayer) {
                Bukkit.broadcast(ChatColor.RED + "Spilleren " + player.getName() + " ble sparket for å bryte brannmurens bevegelsesregler.", "norskebranmur.notify");
            }
        }
    }

    @EventHandler
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        if (!checkOtherActivity) return;
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        String command = event.getMessage().split(" ")[0].substring(1).toLowerCase();

        if (whitelistedCommands.contains(command)) {
            return;
        }

        long currentTime = System.currentTimeMillis();
        lastCommandTime.put(playerId, currentTime);

        int violations = playerCommandViolations.getOrDefault(playerId, 0);
        if (currentTime - lastCommandTime.getOrDefault(playerId, 0L) < commandCooldown) {
            playerCommandViolations.put(playerId, violations + 1);
        }

        if (violations >= maxCommandViolations) {
            player.kickPlayer(playerBlockMessage);
            if (notifyAdminsBlockPlayer) {
                Bukkit.broadcast(ChatColor.RED + "Spilleren " + player.getName() + " ble sparket for å bryte brannmurens kommandoaktivitetsregler.", "norskebranmur.notify");
            }
        }
    }


    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!checkOtherActivity) return;
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        if (lastPacketTime.containsKey(playerId) && System.currentTimeMillis() - lastPacketTime.get(playerId) < packetCooldown) {
            return;
        }
        lastPacketTime.put(playerId, System.currentTimeMillis());
        int violations = playerOtherViolations.getOrDefault(playerId, 0);
        playerOtherViolations.put(playerId, violations + 1);
        if (violations >= maxPacketViolations) {
            // Sjekk for DDoS-angrep basert på nettverkstrafikk eller andre kriterier
            if (isDDoSAttack(player)) {
                handleDDoSAttack(player);
            } else {
                player.kickPlayer(playerBlockMessage);
                if (notifyAdminsBlockPlayer) {
                    Bukkit.broadcast(ChatColor.RED + "Spilleren " + player.getName() + " ble sparket for å bryte brannmurens andre aktivitetsregler.", "norskebranmur.notify");
                }
            }
        }
    }

    private void loadConfig() {
        maxMovementViolations = config.getInt("anti-cheat.max-movement-violations", 10);
        maxOtherViolations = config.getInt("anti-cheat.max-other-violations", 10);
        checkMovement = config.getBoolean("anti-cheat.check-movement", true);
        checkOtherActivity = config.getBoolean("anti-cheat.check-other-activity", true);
        playerBlockMessage = ChatColor.translateAlternateColorCodes('&', config.getString("messages.player-block", "&cYou have been blocked!"));
        playerWarningMessage = ChatColor.translateAlternateColorCodes('&', config.getString("messages.player-warning", "&cWarning!"));
        notifyAdminsBlockPlayer = config.getBoolean("notify-admins-block-player", true);
        notifyAdminsBlockIP = config.getBoolean("notify-admins-block-ip", true);
        blockedIPs = new ArrayList<>(config.getStringList("blocked-ips"));
        blockedPorts = new ArrayList<>(config.getIntegerList("blocked-ports"));
        blockedCommands = new ArrayList<>(config.getStringList("blocked-commands"));
        whitelistedIPs = new ArrayList<>(config.getStringList("whitelist.ip-addresses"));
        enableIPWhitelist = config.getBoolean("whitelist.enable-ip-whitelist", false);
        enableCommandWhitelist = config.getBoolean("whitelist.enable-command-whitelist", false);
        whitelistedCommands = new ArrayList<>(config.getStringList("whitelist.commands"));
        commandCooldown = config.getLong("command-cooldown", 1000);
        movementCooldown = config.getLong("movement-cooldown", 1000);
        packetCooldown = config.getLong("packet-cooldown", 200);
        maxCommandViolations = config.getInt("max-command-violations", 5);
        maxPacketViolations = config.getInt("max-packet-violations", 15);
        maxPacketsPerIP = config.getInt("max-packets-per-ip", 100);
    }

    private void startFirewallMaintenance() {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    // Oppdater blokkerte IP-er og porter
                    updateBlockedIPsAndPorts();

                    // Oppdater blokkerte kommandoer
                    updateBlockedCommands();

                    // Oppdater IP-hviteliste
                    updateIPWhitelist();

                    // Oppdater kommando-hviteliste
                    updateCommandWhitelist();

                    // Logg vellykket vedlikehold
                    LOGGER.info("Brannmurvedlikehold utført.");
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "Feil under brannmurvedlikehold:", e);
                }
            }
        }.runTaskTimer(this, 600L, 600L); // Kjør oppgaven hvert 10. minutt (600 ticks)
    }

    private void updateBlockedIPsAndPorts() {
        // Hent oppdatert liste over blokkerte IP-er fra konfigurasjonen
        List<String> newBlockedIPs = config.getStringList("blocked-ips");

        // Hent oppdatert liste over blokkerte porter fra konfigurasjonen
        List<Integer> newBlockedPorts = config.getIntegerList("blocked-ports");

        // Oppdater listen over blokkerte IP-er
        blockedIPs = new ArrayList<>(newBlockedIPs);

        // Oppdater listen over blokkerte porter
        blockedPorts = new ArrayList<>(newBlockedPorts);

        // Logg oppdateringen
        LOGGER.info("Blokkerte IP-er og porter er oppdatert.");
    }

    private void updateBlockedCommands() {
        // Hent oppdatert liste over blokkerte kommandoer fra konfigurasjonen
        List<String> newBlockedCommands = config.getStringList("blocked-commands");

        // Oppdater listen over blokkerte kommandoer
        blockedCommands = new ArrayList<>(newBlockedCommands);

        // Logg oppdateringen
        LOGGER.info("Blokkerte kommandoer er oppdatert.");
    }

    private void updateIPWhitelist() {
        // Hent oppdatert liste over hvitelistede IP-er fra konfigurasjonen
        List<String> newWhitelistedIPs = config.getStringList("whitelist.ip-addresses");

        // Oppdater listen over hvitelistede IP-er
        whitelistedIPs = new ArrayList<>(newWhitelistedIPs);

        // Logg oppdateringen
        LOGGER.info("IP-hviteliste er oppdatert.");
    }

    private void updateCommandWhitelist() {
        // Hent oppdatert liste over hvitelistede kommandoer fra konfigurasjonen
        List<String> newWhitelistedCommands = config.getStringList("whitelist.commands");

        // Oppdater listen over hvitelistede kommandoer
        whitelistedCommands = new ArrayList<>(newWhitelistedCommands);

        // Logg oppdateringen
        LOGGER.info("Kommando-hviteliste er oppdatert.");
    }

    private void startAntiCheatMonitoring() {
        new BukkitRunnable() {
            @Override
            public void run() {
                // Implementer logikk for overvåkning av anti-cheat her
                try {
                    // Feilhåndtering og logging for anti-cheat-overvåkning
                    LOGGER.info("Anti-cheat-overvåkning utført.");
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "Feil under anti-cheat-overvåkning:", e);
                }
            }
        }.runTaskTimer(this, 20L, 20L); // Kjør oppgaven hvert sekund (20 ticks)
    }

    private void savePluginConfig() {
        try {
            this.getConfig().save(new File(this.getDataFolder(), "config.yml"));
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Could not save config to config.yml", e);
        }
    }

    // Nye funksjoner for å håndtere spesifikke trusler eller behov
    private void handleDDoSAttack(Player player) {
        String playerName = player.getName();
        String playerIP = player.getAddress().getAddress().getHostAddress();

        // Logg hendelsen
        LOGGER.info("Mulig DDoS-angrep oppdaget fra spiller " + playerName + " med IP " + playerIP);

        // Sperk spilleren midlertidig
        player.kickPlayer("Din IP-adresse er midlertidig blokkert på grunn av mistenkelig aktivitet.");

        // Legg til IP-adressen i listen over blokkerte IP-er
        bannedIPs.add(playerIP);

        // Varsle administratorer
        if (notifyAdminsBlockIP) {
            Bukkit.broadcast(ChatColor.RED + "IP-adressen " + playerIP + " er blokkert av brannmuren grunnet mulig DDoS-angrep.", "norskebranmur.notify");
        }
    }

    private void handleExploitAttempt(Player player, Material material) {
        String playerName = player.getName();
        String blockName = material.name();

        // Logg hendelsen
        LOGGER.info("Mulig utnytelsesforsøk oppdaget fra spiller " + playerName + " med blokk " + blockName);

        // Sperk spilleren midlertidig
        player.kickPlayer("Din aktivitet er blokkert på grunn av mistenkelig aktivitet.");

        // Varsle administratorer
        if (notifyAdminsBlockPlayer) {
            Bukkit.broadcast(ChatColor.RED + "Spilleren " + playerName + " ble sparket for å forsøke å utnytte en sårbarhet med blokken " + blockName + ".", "norskebranmur.notify");
        }
    }
}
