import { display, window } from '@kit.ArkUI'
import { emitter } from '@kit.BasicServicesKit'

/*
 * ============================================================
 * WALKIE V24.9.0
 * 全局悬浮 PTT
 *
 * 按住 = 开始说话
 * 松开 = 停止说话
 * 拖动 = 移动悬浮按钮
 * 松手 = 自动吸附左右边缘
 *
 * 注意：
 * 本文件不创建 WalkieClient。
 * 只通过 emitter 与主页面 Index.ets 通信。
 * ============================================================
 */

export const WALKIE_FLOAT_PTT_DOWN_ID: number = 240901
export const WALKIE_FLOAT_PTT_UP_ID: number = 240902

export const WALKIE_FLOAT_PTT_CONNECTED_ID: number = 240903
export const WALKIE_FLOAT_PTT_DISCONNECTED_ID: number = 240904

export const WALKIE_FLOAT_PTT_IDLE_ID: number = 240905
export const WALKIE_FLOAT_PTT_REQUESTING_ID: number = 240906
export const WALKIE_FLOAT_PTT_TALKING_ID: number = 240907
export const WALKIE_FLOAT_PTT_BUSY_ID: number = 240908

function createEmitterEvent(eventId: number): emitter.InnerEvent {
  const event: emitter.InnerEvent = {
    eventId: eventId
  }
  return event
}

const EVENT_PTT_DOWN: emitter.InnerEvent =
  createEmitterEvent(WALKIE_FLOAT_PTT_DOWN_ID)

const EVENT_PTT_UP: emitter.InnerEvent =
  createEmitterEvent(WALKIE_FLOAT_PTT_UP_ID)

const EVENT_CONNECTED: emitter.InnerEvent =
  createEmitterEvent(WALKIE_FLOAT_PTT_CONNECTED_ID)

const EVENT_DISCONNECTED: emitter.InnerEvent =
  createEmitterEvent(WALKIE_FLOAT_PTT_DISCONNECTED_ID)

const EVENT_IDLE: emitter.InnerEvent =
  createEmitterEvent(WALKIE_FLOAT_PTT_IDLE_ID)

const EVENT_REQUESTING: emitter.InnerEvent =
  createEmitterEvent(WALKIE_FLOAT_PTT_REQUESTING_ID)

const EVENT_TALKING: emitter.InnerEvent =
  createEmitterEvent(WALKIE_FLOAT_PTT_TALKING_ID)

const EVENT_BUSY: emitter.InnerEvent =
  createEmitterEvent(WALKIE_FLOAT_PTT_BUSY_ID)

@Entry
@Component
struct WalkieFloatingPtt {
  @State private connected: boolean = false
  @State private talkStatus: string = 'IDLE'
  @State private pressing: boolean = false

  private floatWindow: window.Window | null = null

  private windowX: number = 0
  private windowY: number = 500

  private screenWidth: number = 0
  private screenHeight: number = 0

  private readonly FLOAT_SIZE: number = 84

  aboutToAppear(): void {
    this.floatWindow = window.findWindow('WALKIE_FLOAT_PTT')

    try {
      const currentDisplay: display.Display =
        display.getDefaultDisplaySync()

      this.screenWidth = currentDisplay.width
      this.screenHeight = currentDisplay.height
    } catch (error) {
      console.error(
        '[WALKIE_FLOAT_PTT] get display failed: ' +
        JSON.stringify(error)
      )
    }

    if (this.screenWidth > 0) {
      this.windowX =
        this.screenWidth - this.FLOAT_SIZE
    }

    emitter.on(
      EVENT_CONNECTED,
      () => {
        this.connected = true
        this.talkStatus = 'IDLE'
      }
    )

    emitter.on(
      EVENT_DISCONNECTED,
      () => {
        this.connected = false
        this.pressing = false
        this.talkStatus = 'IDLE'
      }
    )

    emitter.on(
      EVENT_IDLE,
      () => {
        this.talkStatus = 'IDLE'
      }
    )

    emitter.on(
      EVENT_REQUESTING,
      () => {
        this.talkStatus = 'REQUESTING'
      }
    )

    emitter.on(
      EVENT_TALKING,
      () => {
        this.talkStatus = 'TALKING'
      }
    )

    emitter.on(
      EVENT_BUSY,
      () => {
        this.talkStatus = 'BUSY'
        this.pressing = false
      }
    )
  }

  private startPtt(): void {
    if (!this.connected) {
      return
    }

    if (this.pressing) {
      return
    }

    if (
      this.talkStatus === 'REQUESTING' ||
      this.talkStatus === 'TALKING'
    ) {
      return
    }

    if (this.talkStatus === 'BUSY') {
      return
    }

    this.pressing = true

    try {
      emitter.emit(EVENT_PTT_DOWN)
    } catch (error) {
      this.pressing = false

      console.error(
        '[WALKIE_FLOAT_PTT] emit down failed: ' +
        JSON.stringify(error)
      )
    }
  }

  private stopPtt(): void {
    if (!this.pressing) {
      return
    }

    this.pressing = false

    try {
      emitter.emit(EVENT_PTT_UP)
    } catch (error) {
      console.error(
        '[WALKIE_FLOAT_PTT] emit up failed: ' +
        JSON.stringify(error)
      )
    }
  }

  private moveFloatingWindow(
    event: GestureEvent
  ): void {
    if (this.floatWindow === null) {
      return
    }

    this.windowX += event.offsetX
    this.windowY += event.offsetY

    if (this.windowX < 0) {
      this.windowX = 0
    }

    if (this.windowY < 0) {
      this.windowY = 0
    }

    const maxX: number =
      this.screenWidth - this.FLOAT_SIZE

    if (maxX > 0 && this.windowX > maxX) {
      this.windowX = maxX
    }

    const maxY: number =
      this.screenHeight - this.FLOAT_SIZE

    if (maxY > 0 && this.windowY > maxY) {
      this.windowY = maxY
    }

    try {
      this.floatWindow.moveWindowTo(
        this.windowX,
        this.windowY
      )
    } catch (error) {
      console.error(
        '[WALKIE_FLOAT_PTT] move failed: ' +
        JSON.stringify(error)
      )
    }
  }

  private snapToEdge(): void {
    if (this.floatWindow === null) {
      return
    }

    if (this.screenWidth <= 0) {
      return
    }

    const middleX: number =
      this.screenWidth / 2

    const targetX: number =
      this.windowX + this.FLOAT_SIZE / 2 < middleX
        ? 0
        : this.screenWidth - this.FLOAT_SIZE

    this.windowX =
      targetX < 0 ? 0 : targetX

    try {
      this.floatWindow.moveWindowTo(
        this.windowX,
        this.windowY
      )
    } catch (error) {
      console.error(
        '[WALKIE_FLOAT_PTT] snap failed: ' +
        JSON.stringify(error)
      )
    }
  }

  private getStatusText(): string {
    if (!this.connected) {
      return '离线'
    }

    if (this.talkStatus === 'REQUESTING') {
      return '申请'
    }

    if (this.talkStatus === 'TALKING') {
      return '说话'
    }

    if (this.talkStatus === 'BUSY') {
      return '忙'
    }

    return '待机'
  }

  private getStatusBackgroundColor(): string {
    if (!this.connected) {
      return '#808080'
    }

    if (this.talkStatus === 'REQUESTING') {
      return '#FF9800'
    }

    if (this.talkStatus === 'TALKING') {
      return '#19A974'
    }

    if (this.talkStatus === 'BUSY') {
      return '#F44336'
    }

    return '#1677FF'
  }

  build() {
    Stack() {
      /*
       * ========================================================
       * 主圆形按钮
       *
       * Circle 不使用 fillColor。
       * 使用 backgroundColor。
       * ========================================================
       */

      Circle()
        .width(this.FLOAT_SIZE)
        .height(this.FLOAT_SIZE)
        .backgroundColor(
          this.pressing
            ? '#19A974'
            : this.getStatusBackgroundColor()
        )
        .opacity(0.96)

      /*
       * ========================================================
       * 麦克风
       * ========================================================
       */

      Column() {
        Text('🎙')
          .fontSize(28)
          .fontColor(Color.White)

        Text(
          this.pressing
            ? '松开'
            : '按住'
        )
          .fontSize(11)
          .fontColor(Color.White)
          .fontWeight(FontWeight.Medium)
      }
      .justifyContent(FlexAlign.Center)
      .alignItems(HorizontalAlign.Center)

      /*
       * ========================================================
       * 状态
       * ========================================================
       */

      Text(this.getStatusText())
        .fontSize(10)
        .fontWeight(FontWeight.Bold)
        .fontColor(Color.White)
        .backgroundColor('#66000000')
        .borderRadius(8)
        .padding({
          left: 5,
          right: 5,
          top: 2,
          bottom: 2
        })
        .position({
          x: 5,
          y: 60
        })
    }
    .width(this.FLOAT_SIZE)
    .height(this.FLOAT_SIZE)

    /*
     * ========================================================
     * 按压
     * ========================================================
     */

    .onTouch((event: TouchEvent) => {
      const touches: TouchObject[] =
        event.touches

      if (touches.length <= 0) {
        return
      }

      const touch: TouchObject =
        touches[0]

      if (touch.type === TouchType.Down) {
        this.startPtt()
      } else if (touch.type === TouchType.Up) {
        this.stopPtt()
      } else if (touch.type === TouchType.Cancel) {
        this.stopPtt()
      }
    })

    /*
     * ========================================================
     * 拖动
     * ========================================================
     */

    .gesture(
      PanGesture({
        direction: PanDirection.All
      })
        .onActionUpdate(
          (event: GestureEvent) => {
            this.moveFloatingWindow(event)
          }
        )
        .onActionEnd(() => {
          this.snapToEdge()
        })
        .onActionCancel(() => {
          this.stopPtt()
          this.snapToEdge()
        })
    )
  }
}