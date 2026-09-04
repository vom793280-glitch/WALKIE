import {
  AbilityConstant,
  ConfigurationConstant,
  UIAbility,
  Want,
  abilityAccessCtrl,
  PermissionRequestResult,
  wantAgent,
  WantAgent
} from '@kit.AbilityKit'

import {
  hilog
} from '@kit.PerformanceAnalysisKit'

import {
  window
} from '@kit.ArkUI'

import {
  BusinessError
} from '@kit.BasicServicesKit'

import {
  backgroundTaskManager
} from '@kit.BackgroundTasksKit'


const DOMAIN:
  number =
  0x0000


/*
 * ============================================================
 * WALKIE HarmonyOS EntryAbility
 *
 * 当前阶段：
 *
 *   1. 后台长时任务
 *   2. 锁屏后台运行
 *   3. 后台 UDP / 音频保持
 *   4. 自动重连
 *
 * 当前已经验证成功：
 *
 *   DeviceID
 *   昵称恢复
 *   当前频道恢复
 *   在线人员准确
 *   Opus
 *   AudioRenderer
 *   SPEAKER
 *   VOICE_COMMUNICATION
 *
 * ============================================================
 */


/*
 * ============================================================
 * APP 前后台状态
 * ============================================================
 */

AppStorage.setOrCreate(
  'walkie_app_active',
  false
)


export default class EntryAbility
  extends UIAbility {

  /*
   * ==========================================================
   * 本地状态：
   *
   * 仅用于记录“我们最近一次申请是否成功”。
   *
   * ★注意：
   *
   * 这个变量不再作为系统任务是否存在的唯一依据。
   * ==========================================================
   */

  private backgroundTaskStarted:
    boolean =
    false

  /*
   * 防止同一时间多次申请。
   */

  private backgroundTaskStarting:
    boolean =
    false


  // ============================================================
  // Ability 创建
  // ============================================================

  onCreate(
    want:
      Want,
    launchParam:
      AbilityConstant.LaunchParam
  ):
    void {

    hilog.info(
      DOMAIN,
      'WALKIE',
      '%{public}s',
      'Ability onCreate'
    )

    /*
     * ========================================================
     * APP 第一次启动时申请后台长时任务。
     * ========================================================
     */

    void this.ensureWalkieBackgroundTask()
  }


  // ============================================================
  // Ability 销毁
  // ============================================================

  onDestroy():
    void {

    hilog.info(
      DOMAIN,
      'WALKIE',
      '%{public}s',
      'Ability onDestroy'
    )

    /*
     * Ability 真正销毁时释放连续任务。
     */

    void this.stopWalkieBackgroundTask()
  }


  // ============================================================
  // WindowStage 创建
  // ============================================================

  onWindowStageCreate(
    windowStage:
      window.WindowStage
  ):
    void {

    hilog.info(
      DOMAIN,
      'WALKIE',
      '%{public}s',
      'Ability onWindowStageCreate'
    )


    // ==========================================================
    // 加载主页面
    // ==========================================================

    windowStage.loadContent(
      'pages/Index',
      (
        err:
          BusinessError
      ):
        void => {

        if (
          err.code
        ) {

          hilog.error(
            DOMAIN,
            'WALKIE',
            'Failed to load content. ' +
              'Cause: %{public}s',
            JSON.stringify(
              err
            )
          )

          return
        }

        hilog.info(
          DOMAIN,
          'WALKIE',
          '%{public}s',
          'Succeeded in loading content.'
        )
      }
    )


    // ==========================================================
    // 颜色模式
    // ==========================================================

    try {

      this.context
        .getApplicationContext()
        .setColorMode(
          ConfigurationConstant.ColorMode
            .COLOR_MODE_NOT_SET
        )

    } catch (
      error
    ) {

      const businessError:
        BusinessError =
        error as BusinessError

      hilog.error(
        DOMAIN,
        'WALKIE',
        'Failed to set colorMode. ' +
          'code=%{public}s ' +
          'message=%{public}s',
        `${businessError.code}`,
        businessError.message
      )
    }


    // ==========================================================
    // 请求麦克风权限
    // ==========================================================

    void this.requestMicrophonePermission()
  }


  // ============================================================
  // ★核心★
  //
  // 确保 WALKIE 后台任务存在
  // ============================================================

  private async ensureWalkieBackgroundTask():
    Promise<void> {

    /*
     * ========================================================
     * 正在申请中：
     *
     * 不重复执行。
     * ========================================================
     */

    if (
      this.backgroundTaskStarting
    ) {

      hilog.info(
        DOMAIN,
        'WALKIE_BG',
        '%{public}s',
        '后台任务正在申请中，跳过重复申请'
      )

      return
    }


    /*
     * ========================================================
     * 进入申请状态。
     * ========================================================
     */

    this.backgroundTaskStarting =
      true


    try {

      // ======================================================
      // 创建通知点击后的 WantAgent
      // ======================================================

      const wantAgentInfo:
        wantAgent.WantAgentInfo = {

        wants: [
          {
            bundleName:
              this.context
                .abilityInfo
                .bundleName,

            abilityName:
              this.context
                .abilityInfo
                .name
          }
        ],

        operationType:
          wantAgent.OperationType
            .START_ABILITY,

        requestCode:
          1001,

        wantAgentFlags: [
          wantAgent.WantAgentFlags
            .UPDATE_PRESENT_FLAG
        ]
      }


      // ======================================================
      // 获取 WantAgent
      // ======================================================

      const wantAgentObj:
        WantAgent =
        await wantAgent.getWantAgent(
          wantAgentInfo
        )


      // ======================================================
      // WALKIE 长时任务类型
      //
      // audioPlayback
      // audioRecording
      // dataTransfer
      // ======================================================

      const bgModes:
        Array<string> = [

        'audioPlayback',

        'audioRecording',

        'dataTransfer'
      ]


      // ======================================================
      // 申请 Continuous Task
      // ======================================================

      const notification:
        backgroundTaskManager
          .ContinuousTaskNotification =
        await backgroundTaskManager
          .startBackgroundRunning(
            this.context,
            bgModes,
            wantAgentObj
          )


      /*
       * ========================================================
       * ★申请成功★
       * ========================================================
       */

      this.backgroundTaskStarted =
        true

      hilog.info(
        DOMAIN,
        'WALKIE_BG',
        '★后台长时任务重新确认成功★ ' +
          'notificationId=%{public}s',
        `${notification.notificationId}`
      )

      hilog.info(
        DOMAIN,
        'WALKIE_BG',
        'backgroundModes=%{public}s',
        bgModes.join(',')
      )

    } catch (
      error
    ) {

      const businessError:
        BusinessError =
        error as BusinessError

      /*
       * ========================================================
       * 9800005
       *
       * 官方含义：
       *
       * Continuous task verification failed
       *
       * 我们这里重点处理：
       *
       * 应用已经存在一个 Continuous Task。
       *
       * 这不是 fatal error。
       *
       * 如果系统原来的任务还在：
       *
       *     保持不动。
       *
       * 如果系统任务已经失效：
       *
       *     下一次进入后台时再申请。
       * ========================================================
       */

      if (
        businessError.code ===
          9800005
      ) {

        hilog.info(
          DOMAIN,
          'WALKIE_BG',
          '%{public}s',
          'Continuous Task 已存在，跳过重复创建'
        )

      } else if (
        businessError.code ===
          9800006
      ) {

        /*
         * 9800006：
         *
         * Notification verification failed.
         *
         * 这个错误与通知本身有关。
         */

        hilog.error(
          DOMAIN,
          'WALKIE_BG',
          '后台通知校验失败 ' +
            'code=%{public}s ' +
            'message=%{public}s',
          `${businessError.code}`,
          businessError.message
        )

      } else {

        hilog.error(
          DOMAIN,
          'WALKIE_BG',
          '★后台长时任务申请失败★ ' +
            'code=%{public}s ' +
            'message=%{public}s',
          `${businessError.code}`,
          businessError.message
        )
      }

    } finally {

      /*
       * 允许下一次进入后台重新检查。
       */

      this.backgroundTaskStarting =
        false
    }
  }


  // ============================================================
  // 停止 WALKIE 后台任务
  // ============================================================

  private async stopWalkieBackgroundTask():
    Promise<void> {

    /*
     * 只有我们确认曾经成功申请过，
     * 才主动停止。
     */

    if (
      !this.backgroundTaskStarted
    ) {

      return
    }

    try {

      await backgroundTaskManager
        .stopBackgroundRunning(
          this.context
        )

      hilog.info(
        DOMAIN,
        'WALKIE_BG',
        '%{public}s',
        '后台长时任务已停止'
      )

    } catch (
      error
    ) {

      const businessError:
        BusinessError =
        error as BusinessError

      hilog.error(
        DOMAIN,
        'WALKIE_BG',
        '停止后台任务失败 ' +
          'code=%{public}s ' +
          'message=%{public}s',
        `${businessError.code}`,
        businessError.message
      )
    }

    this.backgroundTaskStarted =
      false
  }


  // ============================================================
  // 麦克风运行时权限
  // ============================================================

  private async requestMicrophonePermission():
    Promise<void> {

    try {

      const atManager:
        abilityAccessCtrl.AtManager =
        abilityAccessCtrl.createAtManager()


      const permissionResult:
        PermissionRequestResult =
        await atManager
          .requestPermissionsFromUser(
            this.context,
            [
              'ohos.permission.MICROPHONE'
            ]
          )


      hilog.info(
        DOMAIN,
        'WALKIE_PERMISSION',
        'permissions=%{public}s',
        JSON.stringify(
          permissionResult.permissions
        )
      )


      hilog.info(
        DOMAIN,
        'WALKIE_PERMISSION',
        'authResults=%{public}s',
        JSON.stringify(
          permissionResult.authResults
        )
      )


      hilog.info(
        DOMAIN,
        'WALKIE_PERMISSION',
        'dialogShownResults=%{public}s',
        JSON.stringify(
          permissionResult.dialogShownResults
        )
      )


      /*
       * 0 = PERMISSION_GRANTED
       */

      if (
        permissionResult.authResults.length >
          0 &&
        permissionResult.authResults[0] ===
          0
      ) {

        hilog.info(
          DOMAIN,
          'WALKIE_PERMISSION',
          '%{public}s',
          'MICROPHONE 授权成功'
        )

      } else {

        hilog.error(
          DOMAIN,
          'WALKIE_PERMISSION',
          '%{public}s',
          'MICROPHONE 未获得授权'
        )
      }

    } catch (
      error
    ) {

      const businessError:
        BusinessError =
        error as BusinessError

      hilog.error(
        DOMAIN,
        'WALKIE_PERMISSION',
        'MICROPHONE 权限申请失败，' +
          'code=%{public}s ' +
          'message=%{public}s',
        `${businessError.code}`,
        businessError.message
      )
    }
  }


  // ============================================================
  // WindowStage 销毁
  // ============================================================

  onWindowStageDestroy():
    void {

    hilog.info(
      DOMAIN,
      'WALKIE',
      '%{public}s',
      'Ability onWindowStageDestroy'
    )
  }


  // ============================================================
  // 前台
  // ============================================================

  onForeground():
    void {

    hilog.info(
      DOMAIN,
      'WALKIE',
      '%{public}s',
      'Ability onForeground'
    )


    /*
     * ========================================================
     * APP 回到前台。
     * ========================================================
     */

    AppStorage.setOrCreate(
      'walkie_app_active',
      true
    )


    /*
     * ========================================================
     * 这里不主动 stopBackgroundRunning。
     *
     * 当前 Continuous Task 如果还存在，
     * 就让它继续存在。
     *
     * 如果之前因为系统策略已经失效，
     * 下一次 onBackground() 会重新申请。
     * ========================================================
     */

    hilog.info(
      DOMAIN,
      'WALKIE_BG',
      '%{public}s',
      'WALKIE 回到前台，保留后台任务状态'
    )
  }


  // ============================================================
  // ★关键★
  //
  // 后台
  // ============================================================

  onBackground():
    void {

    hilog.info(
      DOMAIN,
      'WALKIE',
      '%{public}s',
      'Ability onBackground'
    )


    /*
     * ========================================================
     * APP 进入后台。
     *
     * 不关闭：
     *
     *   UDP
     *   WalkieClient
     *   AudioRenderer
     *
     * ========================================================
     */

    AppStorage.setOrCreate(
      'walkie_app_active',
      false
    )


    hilog.info(
      DOMAIN,
      'WALKIE_BG',
      '%{public}s',
      'WALKIE 进入后台，准备重新确认 Continuous Task'
    )


    /*
     * ========================================================
     * ★核心★
     *
     * 不再判断：
     *
     *   backgroundTaskStarted
     *
     * 是否为 true。
     *
     * 因为：
     *
     * 我们无法靠这个变量知道系统任务
     * 是否已经被系统撤销。
     *
     * 每次真正进入后台：
     *
     *     都重新向系统申请一次。
     *
     * 两种结果：
     *
     * 1. 系统任务已经存在
     *
     *      → 9800005
     *      → 说明已有任务
     *      → 不影响现有任务
     *
     * 2. 系统任务已经失效
     *
     *      → 本次申请成功
     *      → 通知栏重新出现
     *
     * ========================================================
     */

    void this.ensureWalkieBackgroundTask()
  }
}