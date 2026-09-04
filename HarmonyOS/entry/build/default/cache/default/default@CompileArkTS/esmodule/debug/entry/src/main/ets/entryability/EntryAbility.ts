import type AbilityConstant from "@ohos:app.ability.AbilityConstant";
import UIAbility from "@ohos:app.ability.UIAbility";
import type Want from "@ohos:app.ability.Want";
import abilityAccessCtrl from "@ohos:abilityAccessCtrl";
import window from "@ohos:window";
import type { BusinessError } from "@ohos:base";
import notificationManager from "@ohos:notificationManager";
import { WalkieBackgroundTask } from "@normalized:N&&&entry/src/main/ets/WalkieBackgroundTask&";
/*
 * ============================================================
 * WALKIE HarmonyOS
 *
 * V24.9.0
 *
 * EntryAbility
 *
 * 本版本：
 *
 * 1. 保留 V24.8.6 后台长时任务
 * 2. 保留锁屏后台运行
 * 3. 保留通知权限
 * 4. 保留全局悬浮 PTT
 * 5. 修复麦克风权限声明后没有运行时授权弹窗的问题
 *
 * HarmonyOS 7.0 / API 26
 *
 * ============================================================
 */
export default class EntryAbility extends UIAbility {
    // ==========================================================
    // V24.9.0
    // 全局悬浮 PTT 窗口
    // ==========================================================
    private floatingPttWindow: window.Window | null = null;
    // ==========================================================
    // Ability 创建
    // ==========================================================
    onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
        console.info('WALKIE V24.9.0: EntryAbility.onCreate');
        /*
         * ========================================================
         * 启动后台长时任务
         *
         * 原 V24.8.6 逻辑保持不变。
         * ========================================================
         */
        void WalkieBackgroundTask.start(this.context);
    }
    // ==========================================================
    // WindowStage 创建
    // ==========================================================
    onWindowStageCreate(windowStage: window.WindowStage): void {
        console.info('WALKIE V24.9.0: WindowStage 创建');
        /*
         * ========================================================
         * 加载主页面
         * ========================================================
         */
        windowStage.loadContent('pages/Index', (err: BusinessError): void => {
            if (err.code) {
                console.error('WALKIE V24.9.0: ' +
                    '主页面加载失败 ' +
                    `code=${err.code} ` +
                    `message=${err.message}`);
                return;
            }
            console.info('WALKIE V24.9.0: ' +
                '主页面加载成功');
            /*
             * ====================================================
             * 请求麦克风权限
             *
             * 必须在主页面加载后主动申请。
             *
             * module.json5 负责声明权限，
             * 这里负责真正向用户弹出授权框。
             * ====================================================
             */
            void EntryAbility
                .requestMicrophonePermission(this.context);
            /*
             * ====================================================
             * 请求通知权限
             * ====================================================
             */
            void EntryAbility
                .requestNotificationPermission(this.context);
            /*
             * ====================================================
             * 创建全局悬浮 PTT
             *
             * 主页面加载成功以后再创建。
             * ====================================================
             */
            this.createFloatingPttWindow();
        });
    }
    // ==========================================================
    // 麦克风权限
    // ==========================================================
    private static async requestMicrophonePermission(context: import('@kit.AbilityKit').common.UIAbilityContext): Promise<void> {
        try {
            console.info('WALKIE V24.9.0: ' +
                '准备申请麦克风权限');
            const atManager: abilityAccessCtrl.AtManager = abilityAccessCtrl.createAtManager();
            const result = await atManager.requestPermissionsFromUser(context, [
                'ohos.permission.MICROPHONE'
            ]);
            console.info('WALKIE V24.9.0: ' +
                `麦克风权限申请结果=${JSON.stringify(result)}`);
            const authResults: Array<number> = result.authResults;
            if (authResults.length > 0 &&
                authResults[0] ===
                    abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED) {
                console.info('WALKIE V24.9.0: ' +
                    '★★★★ 麦克风权限已授权 ★★★★');
            }
            else {
                console.error('WALKIE V24.9.0: ' +
                    '❌ 麦克风权限未授权');
            }
        }
        catch (error) {
            const businessError: BusinessError = error as BusinessError;
            console.error('WALKIE V24.9.0: ' +
                '申请麦克风权限失败 ' +
                `code=${businessError.code} ` +
                `message=${businessError.message}`);
            console.error('WALKIE V24.9.0: ' +
                `权限申请异常=${JSON.stringify(error)}`);
        }
    }
    // ==========================================================
    // 获取 AccessTokenId
    // ==========================================================
    private static getAccessTokenId(context: import('@kit.AbilityKit').common.UIAbilityContext): number {
        return context.applicationInfo.accessTokenId;
    }
    // ==========================================================
    // V24.9.0
    // 创建全局悬浮 PTT
    // ==========================================================
    private createFloatingPttWindow(): void {
        /*
         * 已经创建就不重复创建。
         */
        if (this.floatingPttWindow !==
            null) {
            return;
        }
        try {
            /*
             * ======================================================
             * 系统悬浮窗配置
             * ======================================================
             */
            const config: window.Configuration = {
                name: 'WALKIE_FLOAT_PTT',
                windowType: window.WindowType.TYPE_FLOAT,
                ctx: this.context
            };
            /*
             * ======================================================
             * 创建窗口
             * ======================================================
             */
            window.createWindow(config, (error: BusinessError, floatWindow: window.Window): void => {
                if (error.code) {
                    console.error('WALKIE FLOAT PTT: ' +
                        '创建悬浮窗失败 ' +
                        `code=${error.code} ` +
                        `message=${error.message}`);
                    return;
                }
                /*
                 * 保存窗口对象。
                 */
                this.floatingPttWindow =
                    floatWindow;
                console.info('WALKIE FLOAT PTT: ' +
                    '★悬浮窗创建成功★');
                /*
                 * ==================================================
                 * 配置悬浮窗
                 * ==================================================
                 */
                this.setupFloatingPttWindow(floatWindow);
            });
        }
        catch (error) {
            const businessError: BusinessError = error as BusinessError;
            console.error('WALKIE FLOAT PTT: ' +
                'createWindow异常 ' +
                `code=${businessError.code} ` +
                `message=${businessError.message}`);
        }
    }
    // ==========================================================
    // V24.9.0
    // 配置悬浮 PTT 窗口
    // ==========================================================
    private setupFloatingPttWindow(floatWindow: window.Window): void {
        /*
         * ========================================================
         * 窗口大小
         *
         * 与悬浮 PTT 页面保持 84 × 84。
         * ========================================================
         */
        try {
            floatWindow.resize(84, 84);
        }
        catch (error) {
            console.warn('WALKIE FLOAT PTT: ' +
                'resize失败 ' +
                JSON.stringify(error));
        }
        /*
         * ========================================================
         * 初始位置
         * ========================================================
         */
        try {
            floatWindow.moveWindowTo(900, 1100);
        }
        catch (error) {
            console.warn('WALKIE FLOAT PTT: ' +
                '初始位置设置失败 ' +
                JSON.stringify(error));
        }
        /*
         * ========================================================
         * 加载悬浮页面
         * ========================================================
         */
        try {
            floatWindow.setUIContent('pages/WalkieFloatingPtt', (error: BusinessError): void => {
                if (error.code) {
                    console.error('WALKIE FLOAT PTT: ' +
                        '悬浮页面加载失败 ' +
                        `code=${error.code} ` +
                        `message=${error.message}`);
                    return;
                }
                console.info('WALKIE FLOAT PTT: ' +
                    '悬浮页面加载成功');
                /*
                 * ==================================================
                 * 设置透明背景
                 * ==================================================
                 */
                try {
                    floatWindow
                        .setWindowBackgroundColor('#00000000');
                }
                catch (error) {
                    console.warn('WALKIE FLOAT PTT: ' +
                        '透明背景设置失败 ' +
                        JSON.stringify(error));
                }
                /*
                 * ==================================================
                 * 显示悬浮窗
                 * ==================================================
                 */
                try {
                    floatWindow.showWindow();
                    console.info('WALKIE FLOAT PTT: ' +
                        '★★★★ 悬浮 PTT 已显示 ★★★★');
                }
                catch (error) {
                    console.error('WALKIE FLOAT PTT: ' +
                        '显示悬浮窗失败 ' +
                        JSON.stringify(error));
                }
            });
        }
        catch (error) {
            console.error('WALKIE FLOAT PTT: ' +
                'setUIContent异常 ' +
                JSON.stringify(error));
        }
    }
    // ==========================================================
    // 请求通知权限
    // ==========================================================
    private static async requestNotificationPermission(context: import('@kit.AbilityKit').common.UIAbilityContext): Promise<void> {
        try {
            const enabled: boolean = await notificationManager
                .isNotificationEnabled();
            if (enabled) {
                console.info('WALKIE V24.9.0: ' +
                    '通知已经开启');
                return;
            }
            console.info('WALKIE V24.9.0: ' +
                '请求开启通知权限');
            await notificationManager
                .requestEnableNotification(context);
            console.info('WALKIE V24.9.0: ' +
                '通知权限请求完成');
        }
        catch (error) {
            const businessError: BusinessError = error as BusinessError;
            console.error('WALKIE V24.9.0: ' +
                '请求通知权限失败 ' +
                `code=${businessError.code} ` +
                `message=${businessError.message}`);
        }
    }
    // ==========================================================
    // WindowStage 销毁
    // ==========================================================
    onWindowStageDestroy(): void {
        console.info('WALKIE V24.9.0: ' +
            'WindowStage 销毁');
    }
    // ==========================================================
    // 进入前台
    // ==========================================================
    onForeground(): void {
        console.info('WALKIE V24.9.0: ' +
            '★应用进入前台★');
        /*
         * ========================================================
         * 原 V24.8.6 后台恢复机制保持不变。
         * ========================================================
         */
        void WalkieBackgroundTask.start(this.context);
        /*
         * ========================================================
         * 如果悬浮 PTT 不存在，则重新创建。
         * ========================================================
         */
        if (this.floatingPttWindow ===
            null) {
            this.createFloatingPttWindow();
        }
    }
    // ==========================================================
    // 进入后台
    // ==========================================================
    onBackground(): void {
        console.info('WALKIE V24.9.0: ' +
            '★应用进入后台★');
        /*
         * ========================================================
         * 这里绝对不能关闭：
         *
         * UDP
         * KeepAlive
         * 音频接收
         * 音频播放
         * 悬浮 PTT
         *
         * 原 V24.8.6 后台逻辑保持。
         * ========================================================
         */
        if (this.floatingPttWindow ===
            null) {
            this.createFloatingPttWindow();
        }
    }
    // ==========================================================
    // Ability 销毁
    // ==========================================================
    onDestroy(): void {
        console.info('WALKIE V24.9.0: ' +
            'EntryAbility.onDestroy');
        /*
         * ========================================================
         * 正常销毁 Ability 时关闭悬浮窗。
         * ========================================================
         */
        const floatWindow: window.Window | null = this.floatingPttWindow;
        this.floatingPttWindow =
            null;
        if (floatWindow ===
            null) {
            return;
        }
        try {
            floatWindow.destroyWindow();
            console.info('WALKIE FLOAT PTT: ' +
                '悬浮窗已销毁');
        }
        catch (error) {
            console.warn('WALKIE FLOAT PTT: ' +
                '销毁悬浮窗失败 ' +
                JSON.stringify(error));
        }
    }
}
