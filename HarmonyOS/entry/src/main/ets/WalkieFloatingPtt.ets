import {
  display,
  window
} from '@kit.ArkUI'

import {
  emitter
} from '@kit.BasicServicesKit'

/*
 * ============================================================
 * WALKIE HarmonyOS
 *
 * V24.9.0
 *
 * 全局悬浮 PTT
 *
 * 悬浮窗只负责：
 *
 * 1. 显示状态
 * 2. 接收按下
 * 3. 接收松开
 * 4. 拖动
 *
 * 真正的：
 *
 * startTalking()
 * stopTalking()
 *
 * 全部仍然由主页面唯一 WalkieClient 负责。
 *
 * ============================================================
 */

// ============================================================
// Emitter 事件
// ============================================================

export const WALKIE_FLOAT_PTT_DOWN:
  string =
  'WALKIE_FLOAT_PTT_DOWN'

export const WALKIE_FLOAT_PTT_UP:
  string =
  'WALKIE_FLOAT_PTT_UP'

export const WALKIE_FLOAT_PTT_STATE:
  string =
  'WALKIE_FLOAT_PTT_STATE'

// ============================================================
// 悬浮窗状态
// ============================================================

export class WalkieFloatingPttState {

  connected:
    boolean =
    false

  talkStatus:
    string =
    'NONE'

  constructor(
    connected:
      boolean,
    talkStatus:
      string
  ) {

    this.connected =
      connected

    this.talkStatus =
      talkStatus
  }
}

// ============================================================
// 悬浮窗页面
// ============================================================

@Entry
@Component
struct WalkieFloatingPtt {

  // ==========================================================
  // Window
  // ==========================================================

  private floatWindow:
    window.Window | null =
    null

  // ==========================================================
  // UI
  // ==========================================================

  @State connected:
    boolean =
    false

  @State talkStatus:
    string =
    'NONE'

  @State pressing:
    boolean =
    false

  // ==========================================================
  // 屏幕尺寸
  // ==========================================================

  private screenWidth:
    number =
    1080

  private screenHeight:
    number =
    2400

  // ==========================================================
  // 悬浮窗位置
  // ==========================================================

  private positionX:
    number =
    900

  private positionY:
    number =
    1200

  private dragStartX:
    number =
    0

  private dragStartY:
    number =
    0

  // ==========================================================
  // 悬浮窗大小
  // ==========================================================

  private readonly BUTTON_SIZE:
    number =
    84

  // ==========================================================
  // 状态监听
  // ==========================================================

  private stateCallback:
    ((eventData:
      emitter.EventData) => void) |
    null =
    null

  // ==========================================================
  // 生命周期
  // ==========================================================

  aboutToAppear():
    void {

    console.info(
      'WALKIE FLOAT PTT: 页面启动'
    )

    // --------------------------------------------------------
    // 获取当前悬浮窗
    // --------------------------------------------------------

    try {

      this.floatWindow =
        window.findWindow(
          'WALKIE_FLOAT_PTT'
        )

    } catch (
      error
    ) {

      console.error(
        'WALKIE FLOAT PTT: ' +
          'findWindow失败 ' +
          JSON.stringify(
            error
          )
      )
    }

    // --------------------------------------------------------
    // 获取屏幕尺寸
    // --------------------------------------------------------

    try {

      const displayInfo:
        display.Display =
        display.getDefaultDisplaySync()

      this.screenWidth =
        displayInfo.width

      this.screenHeight =
        displayInfo.height

    } catch (
      error
    ) {

      console.warn(
        'WALKIE FLOAT PTT: ' +
          '获取屏幕尺寸失败 ' +
          JSON.stringify(
            error
          )
      )
    }

    // --------------------------------------------------------
    // 获取悬浮窗位置
    // --------------------------------------------------------

    if (
      this.floatWindow !==
        null
    ) {

      try {

        const properties:
          window.WindowProperties =
          this.floatWindow
            .getWindowProperties()

        this.positionX =
          properties.windowRect.left

        this.positionY =
          properties.windowRect.top

      } catch (
        error
      ) {

        console.warn(
          'WALKIE FLOAT PTT: ' +
            '读取窗口位置失败 ' +
            JSON.stringify(
              error
            )
        )
      }
    }

    // --------------------------------------------------------
    // 监听状态
    // --------------------------------------------------------

    this.stateCallback =
      (
        eventData:
          emitter.EventData
      ): void => {

        if (
          eventData.data ===
            undefined
        ) {

          return
        }

        try {

          const state:
            WalkieFloatingPttState =
            eventData.data as
              WalkieFloatingPttState

          this.connected =
            state.connected

          this.talkStatus =
            state.talkStatus

        } catch (
          error
        ) {

          console.error(
            'WALKIE FLOAT PTT: ' +
              '状态解析失败 ' +
              JSON.stringify(
                error
              )
          )
        }
      }

    emitter.on(
      WALKIE_FLOAT_PTT_STATE,
      this.stateCallback
    )

    // --------------------------------------------------------
    // 请求一次当前状态
    // --------------------------------------------------------

    emitter.emit(
      'WALKIE_FLOAT_PTT_STATE'
    )
  }

  aboutToDisappear():
    void {

    if (
      this.stateCallback !==
        null
    ) {

      emitter.off(
        WALKIE_FLOAT_PTT_STATE,
        this.stateCallback
      )

      this.stateCallback =
        null
    }
  }

  // ==========================================================
  // UI
  // ==========================================================

  build() {

    Stack() {

      // ------------------------------------------------------
      // 背景圆
      // ------------------------------------------------------

      Circle()
        .width(
          this.BUTTON_SIZE
        )
        .height(
          this.BUTTON_SIZE
        )
        .fill(
          this.getButtonColor()
        )
        .shadow({
          radius: 12,
          offsetX: 0,
          offsetY: 4
        })

      // ------------------------------------------------------
      // 图标 + 状态
      // ------------------------------------------------------

      Column() {

        Text(
          this.getIcon()
        )
          .fontSize(28)

        Text(
          this.getButtonText()
        )
          .fontSize(10)
          .fontWeight(
            FontWeight.Bold
          )
          .fontColor(
            Color.White
          )
          .margin({
            top: 2
          })

      }
      .width(
        this.BUTTON_SIZE
      )
      .height(
        this.BUTTON_SIZE
      )
      .justifyContent(
        FlexAlign.Center
      )
      .alignItems(
        HorizontalAlign.Center
      )

    }
    .width(
      this.BUTTON_SIZE
    )
    .height(
      this.BUTTON_SIZE
    )

    // ========================================================
    // PTT
    // ========================================================

    .onTouch(
      (
        event:
          TouchEvent
      ) => {

        if (
          event.type ===
            TouchType.Down
        ) {

          if (
            !this.connected
          ) {

            return
          }

          this.pressing =
            true

          emitter.emit(
            WALKIE_FLOAT_PTT_DOWN
          )

          return
        }

        if (
          event.type ===
            TouchType.Up ||
          event.type ===
            TouchType.Cancel
        ) {

          this.pressing =
            false

          emitter.emit(
            WALKIE_FLOAT_PTT_UP
          )
        }
      }
    )

    // ========================================================
    // 拖动
    // ========================================================

    .gesture(

      PanGesture()

        .onActionStart(
          () => {

            this.dragStartX =
              this.positionX

            this.dragStartY =
              this.positionY
          }
        )

        .onActionUpdate(
          (
            event:
              GestureEvent
          ) => {

            const targetX:
              number =
              this.dragStartX +
                event.offsetX

            const targetY:
              number =
              this.dragStartY +
                event.offsetY

            this.positionX =
              this.clampX(
                targetX
              )

            this.positionY =
              this.clampY(
                targetY
              )

            this.moveWindow()
          }
        )

        .onActionEnd(
          () => {

            this.snapToEdge()
          }
        )
    )
  }

  // ==========================================================
  // 窗口移动
  // ==========================================================

  private moveWindow():
    void {

    if (
      this.floatWindow ===
        null
    ) {

      return
    }

    try {

      this.floatWindow
        .moveWindowTo(
          this.positionX,
          this.positionY
        )

    } catch (
      error
    ) {

      console.warn(
        'WALKIE FLOAT PTT: ' +
          'moveWindowTo失败 ' +
          JSON.stringify(
            error
          )
      )
    }
  }

  // ==========================================================
  // X限制
  // ==========================================================

  private clampX(
    value:
      number
  ):
    number {

    const maxX:
      number =
      Math.max(
        0,
        this.screenWidth -
          this.BUTTON_SIZE
      )

    return Math.max(
      0,
      Math.min(
        value,
        maxX
      )
    )
  }

  // ==========================================================
  // Y限制
  // ==========================================================

  private clampY(
    value:
      number
  ):
    number {

    const maxY:
      number =
      Math.max(
        0,
        this.screenHeight -
          this.BUTTON_SIZE
      )

    return Math.max(
      0,
      Math.min(
        value,
        maxY
      )
    )
  }

  // ==========================================================
  // 自动贴边
  // ==========================================================

  private snapToEdge():
    void {

    const centerX:
      number =
      this.screenWidth /
        2

    if (
      this.positionX <
        centerX
    ) {

      this.positionX =
        8

    } else {

      this.positionX =
        Math.max(
          8,
          this.screenWidth -
            this.BUTTON_SIZE -
            8
        )
    }

    this.moveWindow()
  }

  // ==========================================================
  // 颜色
  // ==========================================================

  private getButtonColor():
    string {

    if (
      !this.connected
    ) {

      return '#7E838B'
    }

    if (
      this.talkStatus ===
        'ALLOWED'
    ) {

      return '#27AE60'
    }

    if (
      this.talkStatus ===
        'REQUESTING'
    ) {

      return '#F39C12'
    }

    if (
      this.talkStatus ===
        'BUSY'
    ) {

      return '#61666D'
    }

    if (
      this.pressing
    ) {

      return '#C62828'
    }

    return '#3478F6'
  }

  // ==========================================================
  // 图标
  // ==========================================================

  private getIcon():
    string {

    if (
      !this.connected
    ) {

      return '🔇'
    }

    if (
      this.talkStatus ===
        'BUSY'
    ) {

      return '🚫'
    }

    return '🎙'
  }

  // ==========================================================
  // 文字
  // ==========================================================

  private getButtonText():
    string {

    if (
      !this.connected
    ) {

      return '离线'
    }

    if (
      this.talkStatus ===
        'ALLOWED'
    ) {

      return '讲话中'
    }

    if (
      this.talkStatus ===
        'REQUESTING'
    ) {

      return '抢麦中'
    }

    if (
      this.talkStatus ===
        'BUSY'
    ) {

      return '忙线'
    }

    return '按住说话'
  }
}