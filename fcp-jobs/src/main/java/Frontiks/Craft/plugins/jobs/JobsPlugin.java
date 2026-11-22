package Frontiks.Craft.plugins.jobs;

import Frontiks.Craft.api.EconomyService;
import Frontiks.Craft.api.FcpServices;
import Frontiks.Craft.api.WorkService;
import Frontiks.Craft.commands.FishingGiveCommand;
import Frontiks.Craft.commands.HrabotaGiveCommand;
import Frontiks.Craft.commands.SetGranicCommand;
import Frontiks.Craft.commands.WorkMenuCommand;
import Frontiks.Craft.listeners.QuitListener;
import Frontiks.Craft.listeners.WorkCheckListener;
import Frontiks.Craft.listeners.WorkMenuListener;
import Frontiks.Craft.mining.MiningListener;
import Frontiks.Craft.mining.MiningNodeCommand;
import Frontiks.Craft.mining.MiningNodeManager;
import Frontiks.Craft.mining.MiningNodeMenuListener;
import Frontiks.Craft.mining.MiningNodeStore;
import Frontiks.Craft.mining.MiningSettings;
import Frontiks.Craft.quests.QuestManager;
import Frontiks.Craft.quests.QuestsCommand;
import Frontiks.Craft.quests.QuestsMenuListener;
import Frontiks.Craft.work.BlockBreakListener;
import Frontiks.Craft.work.HrabotaConfig;
import Frontiks.Craft.work.WorkManager;
import org.bukkit.Location;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class JobsPlugin extends JavaPlugin {

    private MiningNodeManager nodeManager;
    private QuestManager questManager;

    @Override
    public void onEnable() {
        HrabotaConfig.init(this);

        MiningSettings settings = new MiningSettings();
        this.questManager = new QuestManager(this, settings);
        this.questManager.init();

        MiningNodeStore nodeStore = new MiningNodeStore(this);
        this.nodeManager = new MiningNodeManager(this, settings, nodeStore, this.questManager);
        this.nodeManager.init();

        getServer().getServicesManager().register(
                WorkService.class,
                new WorkService() {
                    @Override
                    public boolean isWorking(Player p) {
                        return WorkManager.isWorking(p);
                    }

                    @Override
                    public boolean isInWorkRegion(Location l) {
                        return HrabotaConfig.contains(l);
                    }
                },
                this,
                ServicePriority.Normal
        );

        bind("setgranic", new SetGranicCommand());
        bind("work", new WorkMenuCommand());
        bind("workmenu", new WorkMenuCommand());

        HrabotaGiveCommand h = new HrabotaGiveCommand(FcpServices.require(EconomyService.class));
        for (int i = 1; i <= 5; i++) {
            bind("hrabotagive" + i, h);
        }

        FishingGiveCommand f = new FishingGiveCommand(FcpServices.require(EconomyService.class));
        for (int i = 1; i <= 5; i++) {
            bind("fishinggive" + i, f);
        }

        bind("miningnode", new MiningNodeCommand(this, this.nodeManager));
        bind("quests", new QuestsCommand(this, this.questManager, settings));

        var pm = getServer().getPluginManager();
        pm.registerEvents(new QuitListener(), this);
        pm.registerEvents(new WorkMenuListener(), this);
        pm.registerEvents(new WorkCheckListener(), this);
        pm.registerEvents(new MiningListener(this, this.nodeManager), this);
        pm.registerEvents(new MiningNodeMenuListener(this, this.nodeManager), this);
        pm.registerEvents(new QuestsMenuListener(this.questManager, settings), this);
        pm.registerEvents(new BlockBreakListener(this, this.nodeManager), this);

        getLogger().info("FCP Jobs enabled");
    }

    private void bind(String name, CommandExecutor executor) {
        PluginCommand cmd = getCommand(name);
        if (cmd == null) {
            getLogger().warning("Command not in plugin.yml: /" + name);
            return;
        }
        cmd.setExecutor(executor);
        if (executor instanceof TabCompleter tc) {
            cmd.setTabCompleter(tc);
        }
    }

    @Override
    public void onDisable() {
        if (this.nodeManager != null) {
            this.nodeManager.shutdown();
        }
        HrabotaConfig.save();
        getServer().getServicesManager().unregisterAll(this);
    }
}
