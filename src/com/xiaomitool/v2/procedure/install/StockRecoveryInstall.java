package com.xiaomitool.v2.procedure.install;

import com.xiaomitool.v2.adb.AdbCommons;
import com.xiaomitool.v2.adb.AdbCommunication;
import com.xiaomitool.v2.adb.device.Device;
import com.xiaomitool.v2.engine.actions.ActionsDynamic;
import com.xiaomitool.v2.gui.WindowManager;
import com.xiaomitool.v2.gui.visual.ProgressPane;
import com.xiaomitool.v2.language.LRes;
import com.xiaomitool.v2.logging.Log;
import com.xiaomitool.v2.procedure.*;
import com.xiaomitool.v2.procedure.device.RebootDevice;
import com.xiaomitool.v2.process.AdbRunner;
import com.xiaomitool.v2.resources.ResourcesConst;
import com.xiaomitool.v2.rom.Installable;
import com.xiaomitool.v2.tasks.AdbSideloadTask;
import com.xiaomitool.v2.tasks.Task;
import com.xiaomitool.v2.tasks.TaskManager;
import com.xiaomitool.v2.tasks.UpdateListener;
import com.xiaomitool.v2.utility.DriverUtils;
import com.xiaomitool.v2.utility.MTPUtils;
import com.xiaomitool.v2.utility.Pointer;
import com.xiaomitool.v2.utility.utils.StrUtils;
import com.xiaomitool.v2.xiaomi.miuithings.DeviceRequestParams;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static com.xiaomitool.v2.procedure.fetch.RomOtaFetch.chooseChineseSameBranchDifferentVersion;

public class StockRecoveryInstall {
    public static final String SELECTED_MTP_DEVICE = "selected_mtp_device";


    static RInstall enableMtp() {
        return RNode.sequence(new RInstall() {
            @Override
            public void run(ProcedureRunner procedureRunner) throws InstallException, InterruptedException, RMessage {
                Device device = (Device) procedureRunner.requireContext(Procedures.SELECTED_DEVICE);

                HashMap<String, MTPUtils.MTPDevice> initDeviceMap;
                procedureRunner.text(LRes.SEARCHING_CONNECTED_MTP_DEVICES);
                try {
                    initDeviceMap = MTPUtils.list();
                } catch (IOException e) {
                    throw new InstallException("MTP list devices failed: " + e.getMessage(), InstallException.Code.MTP_FAILED, true);
                }
                procedureRunner.text(LRes.MTP_ENABLING_DEVICE);
                String out = AdbCommons.raw(device.getSerial(), "enablemtp:");
                if (out == null) {
                    throw new InstallException("Adb enablemtp command failed, maybe your device doesn't support it or device is not connected", InstallException.Code.ADB_EXCEPTION, true);
                }
                Thread.sleep(2000);
                procedureRunner.text(LRes.SEARCHING_CONNECTED_MTP_DEVICES);
                HashMap<String, MTPUtils.MTPDevice> afterDeviceMap;
                try {
                    afterDeviceMap = MTPUtils.list();
                } catch (IOException e) {
                    throw new InstallException("MTP list devices failed: " + e.getMessage(), InstallException.Code.MTP_FAILED, true);
                }
                String newDeviceKey = null;
                List<MTPUtils.MTPDevice> xiaomiDevices = new ArrayList<>();
                for (String key : afterDeviceMap.keySet()) {
                    if (initDeviceMap.get(key) == null) {
                        newDeviceKey = key;
                    }
                    if (key.toLowerCase().contains("vid_2717")) {
                        xiaomiDevices.add(afterDeviceMap.get(key));
                    }
                }
                if (newDeviceKey != null && newDeviceKey.toLowerCase().contains("vid_2717")) {
                    procedureRunner.text(LRes.MTP_DEVICE_SELECTED);
                    procedureRunner.setContext(SELECTED_MTP_DEVICE, afterDeviceMap.get(newDeviceKey));
                    return;
                }
                for (MTPUtils.MTPDevice device1 : xiaomiDevices) {
                    try {
                        String root = MTPUtils.getRoot(device1);
                        if (root != null && "data".equals(root.toLowerCase().trim())) {
                            procedureRunner.text(LRes.MTP_DEVICE_SELECTED);
                            procedureRunner.setContext(SELECTED_MTP_DEVICE, device1);
                            return;
                        }
                    } catch (IOException e) {
                        Log.warn("Cannot get device root of " + device1.id + ": " + e.getMessage());
                    }
                }
                Integer trial = (Integer) procedureRunner.consumeContext("TRIAL");
                if (trial == null) {
                    trial = 0;
                }
                if (trial < 2) {
                    fixMtpDevices().run(procedureRunner);
                    boolean result = (boolean) procedureRunner.consumeContext("FIXMTP");
                    if (result) {
                        procedureRunner.setContext("TRIAL", trial + 1);
                        Thread.sleep(1000);
                        this.run(procedureRunner);
                        return;
                    }
                }
                throw new InstallException("Cannot detect recovery mtp device, try updating the mtp driver on the device", InstallException.Code.MTP_FAILED, true);


            }
        }, GenericInstall.updateDeviceStatus(null,null,false));
    }

    static RInstall fixMtpDevices() {
        return new RInstall() {
            @Override
            public void run(ProcedureRunner procedureRunner) throws InstallException, InterruptedException {
                procedureRunner.text(LRes.DRIVER_FIXING_MTP);
                boolean res = DriverUtils.fixMtpDevices();
                procedureRunner.setContext("FIXMTP", res);
            }
        };

    }

    static RInstall sendFileViaMTP() {
        return new RInstall() {
            @Override
            public void run(ProcedureRunner procedureRunner) throws InstallException, InterruptedException {
                Installable installable = (Installable) procedureRunner.requireContext(Procedures.INSTALLABLE);
                File finalFile = installable.getFinalFile();
                if (finalFile == null) {
                    throw new InstallException("Null install file", InstallException.Code.INTERNAL_ERROR, false);
                }
                Path file = finalFile.toPath();
                procedureRunner.text(LRes.MTP_SENDING_FILE);
                if (!Files.exists(file)) {
                    throw new InstallException("File " + file.toString() + " doesn't exists!", InstallException.Code.FILE_NOT_FOUND, false);
                }
                MTPUtils.MTPDevice device = (MTPUtils.MTPDevice) procedureRunner.requireContext(SELECTED_MTP_DEVICE);
                Task task = MTPUtils.getPushTask(device, file, "/");
                ProgressPane.DefProgressPane progressPane = new ProgressPane.DefProgressPane();
                progressPane.setContentText(LRes.MTP_SENDING_FILE);

                UpdateListener listener = progressPane.getUpdateListener(100);
                task.setListener(listener);
                WindowManager.setMainContent(progressPane, false);
                TaskManager.getInstance().startSameThread(task);
                WindowManager.removeTopContent();
                Exception error = task.getError();
                if (error != null) {
                    throw new InstallException("Failed to send file to mtp device: " + error.getMessage(), InstallException.Code.MTP_FAILED, true);
                }
                procedureRunner.text(LRes.FILE_SENT_TO_DEVICE);
            }

        };
    }

    static RInstall sidelaodFile() {
        return new RInstall() {
            @Override
            public void run(ProcedureRunner runner) throws InstallException, RMessage, InterruptedException {
                Device device = Procedures.requireDevice(runner);
                Installable installable = Procedures.requireInstallable(runner);
                String token = installable.getInstallToken();
                runner.text(LRes.STARTING_MIUI_SIDELOAD);
                if (StrUtils.isNullOrEmpty(token)){
                    throw new InstallException("Empty install token", InstallException.Code.SIDELOAD_INSTALL_FAILED, false);
                }
                AdbSideloadTask sideloadTask = new AdbSideloadTask(installable.getFinalFile(), token, device.getSerial());
                ProgressPane.DefProgressPane defProgressPane = new ProgressPane.DefProgressPane();
                UpdateListener listener = defProgressPane.getUpdateListener(300);
                final Pointer textInstalling = new Pointer();
                textInstalling.pointed = false;
                listener.addOnUpdate(new UpdateListener.OnUpdate() {
                    @Override
                    public void run(long downloaded, long totalSize, Duration latestDuration, Duration totalDuration) {
                        if (downloaded/totalSize >= 0.5 && !((boolean) textInstalling.pointed)){
                            textInstalling.pointed = true;
                            defProgressPane.setContentText(LRes.SIDELOAD_INSTALLING_FILE.toString()+"\n"+ LRes.DONT_REBOOT_DEVICE);
                        }
                    }
                });
                sideloadTask.setListener(listener);
                defProgressPane.setContentText(LRes.ADB_PUSHING_FILE+"\n"+LRes.DONT_REBOOT_DEVICE);
                defProgressPane.setText(LRes.STARTING_MIUI_SIDELOAD);
                WindowManager.setMainContent(defProgressPane,false);
                TaskManager.getInstance().startSameThread(sideloadTask);
                WindowManager.removeTopContent();
                if (!sideloadTask.isFinished()){
                    Exception e = sideloadTask.getError();
                    throw new InstallException("MIUI sideload failed: "+e.getMessage(), InstallException.Code.SIDELOAD_INSTALL_FAILED, true);
                }


            }
        };
    }

    static RInstall installMtpFile() {
        return new RInstall() {
            @Override
            public void run(ProcedureRunner runner) throws InstallException, InterruptedException {
                Device device = Procedures.requireDevice(runner);
                Installable installable = Procedures.requireInstallable(runner);
                runner.text(LRes.MTP_PREPARE_INSTALL);
                Thread.sleep(2000);


                String token = installable.getInstallToken();
                if (token == null || token.isEmpty()) {
                    throw new InstallException("Empty install token", InstallException.Code.MTP_INSTALL_FAILED, false);
                }
                AdbRunner adbRunner = new AdbRunner("raw", "mtpinstall:" + token);
                adbRunner.setDeviceSerial(device.getSerial());
                runner.text(LRes.MTP_INSTALLING_FILE);
                ProgressPane.DefProgressPane progressPane = new ProgressPane.DefProgressPane();
                progressPane.setProgress(-1d);
                progressPane.setContentText(LRes.MTP_INSTALLING_FILE.toString() + "\n" + LRes.DONT_REBOOT_DEVICE.toString());
                WindowManager.setMainContent(progressPane, false);
                LocalDateTime startTime = LocalDateTime.now();
                AdbCommunication.getAllAccess();
                try {
                    adbRunner.runWait(3600);
                } catch (IOException e) {
                    throw new InstallException("Failed to start mtpinstall process: " + e.getMessage(), InstallException.Code.INTERNAL_ERROR, true);
                }
                AdbCommunication.giveAllAccess();
                WindowManager.removeTopContent();
                Duration timeElapsed = Duration.between(startTime, LocalDateTime.now());
                if (adbRunner.getExitValue() != 0) {
                    throw new InstallException("MtpInstall process returned with code " + adbRunner.getExitValue(), InstallException.Code.MTP_INSTALL_FAILED, true);
                }
                String output = adbRunner.getOutputString();
                if (output.contains("Installation_aborted")) {
                    throw new InstallException("MtpInstallation was aborted by the device: probably wrong token or Xiaomi patched that", InstallException.Code.MTP_INSTALL_FAILED, false);
                }
                long seconds = timeElapsed.getSeconds();
                if (seconds < 8) {
                    throw new InstallException("MtpInstallation took only " + seconds + " seconds to complete, thus it can't be successful", InstallException.Code.MTP_INSTALL_FAILED, false);
                }
                runner.text(LRes.ROM_INSTALLED_ON_DEVICE);

            }
        };
    }

    static RInstall formatData() {
        return RNode.sequence(new RInstall() {
            @Override
            public void run(ProcedureRunner runner) throws InstallException, InterruptedException {
                Device device = (Device) runner.requireContext(Procedures.SELECTED_DEVICE);
                runner.text(LRes.PARTITION_FORMATTING.toString("data"));
                String out = AdbCommons.raw(device.getSerial(), "format-data:");
                if (out == null) {
                    throw new InstallException("Failed to wipe data: null output", InstallException.Code.ADB_EXCEPTION, true);
                }
                runner.text(LRes.PARTITION_FORMATTED);
            }
        }, GenericInstall.updateDeviceStatus(null, null, false));
    }

    @ExportFunction("mtp_stockrecovery_install")
    public static RInstall mtpFlashRom() {
        return RNode.sequence(new RInstall() {
            @Override
            public void run(ProcedureRunner runner) throws InstallException, RMessage, InterruptedException {
                if (!ResourcesConst.isWindows()){
                    throw new InstallException("This operation is not supported by this os", InstallException.Code.OS_NOT_SUPPORTED, false);
                }
            }
        },RebootDevice.requireStockRecovery(), enableMtp(), sendFileViaMTP(), installMtpFile(), formatData());
    }

    public static RInstall getStockRecoveryInfo() {
        return new RInstall() {
            @Override
            public void run(ProcedureRunner procedureRunner) throws InstallException, InterruptedException {
                procedureRunner.text(LRes.FETCHING_RECOVERY_INFO);
                Device device = (Device) procedureRunner.requireContext(Procedures.SELECTED_DEVICE);
                ActionsDynamic.REQUIRE_DEVICE_CONNECTED(device).run();
                String serial = device.getSerial();
                String sn = AdbCommons.raw(serial, "getsn:");
                String version = AdbCommons.raw(serial, "getversion:");
                String codebase = AdbCommons.raw(serial, "getcodebase:");
                String dev = AdbCommons.raw(serial, "getdevice:");
                String zone = AdbCommons.raw(serial, "getromzone:");
                if (dev == null || version == null || sn == null || codebase == null) {
                    throw new InstallException("Failed to retrieve recovery information: null param", InstallException.Code.INFO_RETRIVE_FAILED, true);
                }
                int z;
                try {
                    z = Integer.parseInt(zone);
                    if (z != 1 && z != 2) {
                        throw new Exception();
                    }
                } catch (Throwable t) {
                    z = dev.contains("_global") ? 2 : 1;
                }
                DeviceRequestParams deviceRequestParams = new DeviceRequestParams(dev, version, codebase, null, sn, z);
                procedureRunner.setContext(Procedures.REQUEST_PARAMS, deviceRequestParams);
            }
        };
    }

    public static RInstall recoverStuckDevice() {
        return RNode.sequence(getStockRecoveryInfo(), chooseChineseSameBranchDifferentVersion(), mtpFlashRom(), Procedures.rebootIfYouCan(Device.Status.DEVICE), GenericInstall.installationSuccess());

    }

    @ExportFunction("side_stockrecovery_install")
    static RInstall sideloadFlash(){
        return RNode.sequence(RebootDevice.requireStockRecovery(), sidelaodFile(), formatData());
    }

    @ExportFunction("stockrecovery_install")
    public static RInstall stockRecoveryInstall() {
        if (ResourcesConst.isWindows()){
            return RNode.fallback(sideloadFlash(), mtpFlashRom());
        } else {
            return sideloadFlash();
        }
    }


}