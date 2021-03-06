package de.neuland.firefly;

import de.hybris.platform.core.Registry;
import de.neuland.firefly.changes.Change;
import de.neuland.firefly.changes.ChangeFactory;
import de.neuland.firefly.changes.ChangeList;
import de.neuland.firefly.extensionfinder.FireflyExtensionRepository;
import de.neuland.firefly.extensionfinder.FireflySystem;
import de.neuland.firefly.extensionfinder.FireflySystemFactory;
import de.neuland.firefly.migration.MigrationRepository;
import de.neuland.firefly.model.FireflyMigrationModel;
import org.apache.log4j.Logger;
import org.springframework.stereotype.Service;

import java.util.List;


/**
 * This service holds the main public interface for the Firefly migration tools.
 */
@Service
public class FireflyService {
    private static final Logger LOG = Logger.getLogger(FireflyService.class);

    public void baseline() {
        LOG.info("Setting baseline for tenant " + Registry.getCurrentTenant() + " ...");
        try {
            performBaseline();
        } catch (FireflyExtensionRepository.FireflyNotInstalledException e) {
            installFirefly();
            performBaseline();
        }
        LOG.info("...baseline has been set.");
    }

    private void performBaseline() throws FireflyExtensionRepository.FireflyNotInstalledException {
        FireflySystemFactory fireflySystemFactory = Registry.getGlobalApplicationContext().getBean(FireflySystemFactory.class);
        FireflySystem fireflySystem = fireflySystemFactory.createFireflySystem();
        try {
            FireflyMigrationModel migration = null;
            if (fireflySystem.isUpdateRequired() || fireflySystem.isHmcResetRequired()) {
                migration = getOrCreateMigration(migration);
                fireflySystem.setBaseline(migration);
            }
            ChangeList changeList = Registry.getGlobalApplicationContext().getBean(ChangeFactory.class).createChangeList();
            if (!changeList.getChangesThatRequiredExecution().isEmpty()) {
                migration = getOrCreateMigration(migration);
                changeList.markChangesAsExecuted(migration);
            }
        } catch (FireflyExtensionRepository.FireflyNotInstalledException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            fireflySystemFactory.destroyFireflySystem(fireflySystem);
        }
    }

    public void migrate() {
        try {
            LOG.info("Starting migration for tenant " + Registry.getCurrentTenant() + " ...");
            performMigration();
            LOG.info("...migration complete.");
        } catch (FireflyExtensionRepository.FireflyNotInstalledException e) {
            installFirefly();
            performMigration();
        }
    }

    private void performMigration() throws FireflyExtensionRepository.FireflyNotInstalledException {
        FireflySystemFactory fireflySystemFactory = Registry.getGlobalApplicationContext().getBean(FireflySystemFactory.class);
        FireflySystem fireflySystem = fireflySystemFactory.createFireflySystem();
        try {
            FireflyMigrationModel migration = null;
            if (fireflySystem.isUpdateRequired() || fireflySystem.isHmcResetRequired()) {
                migration = getOrCreateMigration(migration);
                fireflySystem.update(migration);
            }
            ChangeList changeList = Registry.getGlobalApplicationContext().getBean(ChangeFactory.class).createChangeList();
            if (!changeList.getChangesThatRequiredExecution().isEmpty()) {
                migration = getOrCreateMigration(migration);
                changeList.executeChanges(migration);
            }
            if (migration != null) {
                Registry.getGlobalApplicationContext().getBean(HybrisAdapter.class).clearJaloCache();
            }
        } catch (FireflyExtensionRepository.FireflyNotInstalledException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            fireflySystemFactory.destroyFireflySystem(fireflySystem);
        }
    }

    private void installFirefly() {
        LOG.warn("...Firefly is not installed yet. A update is performed to install firefly.");
        try {
            Registry.getGlobalApplicationContext().getBean(HybrisAdapter.class).initFirefly();
            LOG.info("...Firefly has been installed.");
        } catch (Exception initException) {
            throw new RuntimeException(initException);
        }
    }

    private FireflyMigrationModel getOrCreateMigration(FireflyMigrationModel migration) {
        if (migration == null) {
            MigrationRepository migrationRepository = Registry.getGlobalApplicationContext().getBean(MigrationRepository.class);
            FireflyMigrationModel result = migrationRepository.create();
            migrationRepository.save(result);
            return result;
        } else {
            return migration;
        }
    }

    public String simulate() {
        StringBuilder resultMessage = new StringBuilder();
        resultMessage.append("Running simulation: \n");

        FireflySystemFactory fireflySystemFactory = Registry.getGlobalApplicationContext().getBean(FireflySystemFactory.class);
        ChangeFactory changeFactory = Registry.getGlobalApplicationContext().getBean(ChangeFactory.class);
        FireflySystem fireflySystem = fireflySystemFactory.createFireflySystem();
        try {
            resultMessage.append("- ").append(fireflySystem.isUpdateRequired() ? "System update is required" : "System update is not required").append("\n");
            resultMessage.append("- ").append(fireflySystem.isHmcResetRequired() ? "hMC reset is required" : "hMC reset is not required").append("\n");

            try {
                List<Change> changesThatRequiredExecution = changeFactory.createChangeList().getChangesThatRequiredExecution();
                if (changesThatRequiredExecution.isEmpty()) {
                    resultMessage.append("- No new changes found.");
                } else {
                    resultMessage.append("- Changes found:").append("\n");
                    for (Change change : changesThatRequiredExecution) {
                        resultMessage.append("\t").append(change).append("\n");
                    }
                }
            } catch (Change.ChangeModifiedException changeModifiedException) {
                resultMessage.append("- ").append(changeModifiedException.getMessage());
            }
        } catch (FireflyExtensionRepository.FireflyNotInstalledException e) {
            resultMessage.append("Firefly is not installed yet. A update is required to run simulation.");
        } finally {
            fireflySystemFactory.destroyFireflySystem(fireflySystem);
        }
        LOG.info(resultMessage.toString());
        return resultMessage.toString();
    }
}
