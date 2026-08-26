/* 触屏手柄（issue #30/#35）：MV/MZ 共用。
 * 由 TyranoActivity 拼接进引擎 hook 注入；按键通过合成 keydown/keyup
 * （keyCode）派发，MV 与 MZ 的 Input 均读 keyCode，事件模型一致。
 *
 * 布局说明：所有尺寸/位置由 layout() 统一计算，锚定全视口
 * （window.innerWidth/innerHeight），portrait 模式下自然利用
 * letterbox 黑边空间；监听 resize/orientationchange 重排。
 * 布局完成后发布 window.__touchPadMetrics 并派发
 * tyranorpadlayout 事件，供修改器悬浮球避让动作键列。 */
(function () {
  if (window.__tyranorTouchPadInjected) return
  window.__tyranorTouchPadInjected = true

  const ROOT_ID = 'tyranor-touch-pad-root'

  function initTouchPad() {
    const oldRoot = document.getElementById(ROOT_ID)
    if (oldRoot) oldRoot.remove()
    const rootElement = document.createElement('div')
    rootElement.id = ROOT_ID
    document.body.appendChild(rootElement)

  let padSize = 0
  let joyStickSR = 0
  let joyStickR = 0
  let joyStickCX = 0
  let joyStickCY = 0
  const allMargin = 10
  const lrMargin = 50
  let isKeysShown = true
  let useJoyStick = true
  let useDir8 = false
  const udlrEvents = {
    Up: false,
    Left: false,
    Right: false,
    Down: false
  }
  const joyStickStage = document.createElement('div')
  const joyStick = document.createElement('div')
  const actionsElement = document.createElement('div')
  const keySwitchElement = document.createElement('div')
  keySwitchElement.innerText = isKeysShown ? 'Hide' : 'Show'
  const joyStickSwitchElement = document.createElement('div')
  joyStickSwitchElement.innerText = useJoyStick ? 'Button' : 'Stick'
  const dir8SwitchElement = document.createElement('div')
  dir8SwitchElement.innerText = useDir8 ? '4 Dir' : '8 Dir'
  const udlrElement = document.createElement('div')
  const qwzxElement = document.createElement('div')
  rootElement.appendChild(actionsElement)
  actionsElement.appendChild(keySwitchElement)
  actionsElement.appendChild(joyStickSwitchElement)
  actionsElement.appendChild(dir8SwitchElement)
  rootElement.appendChild(qwzxElement)
  rootElement.appendChild(joyStickStage)
  joyStickStage.appendChild(joyStick)
  rootElement.appendChild(udlrElement)
  const keyCodes = {
    Tab: 9,
    Enter: 13,
    Shift: 16,
    Ctrl: 17,
    Alt: 18,
    Esc: 27,
    Space: 32,
    PageUp: 33,
    PageDown: 34,
    Left: 37,
    Up: 38,
    Right: 39,
    Down: 40,
    Q: 81,
    W: 87,
    X: 88,
    Z: 90
  }
  const actionsBtns = [
    {
      text: 'PageUp',
      keyCode: keyCodes.PageUp
    },
    {
      text: 'PageDown',
      keyCode: keyCodes.PageDown
    },
    {
      text: 'Tab',
      keyCode: keyCodes.Tab
    },
    {
      text: 'Alt',
      keyCode: keyCodes.Alt
    },
    {
      text: 'Ctrl',
      keyCode: keyCodes.Ctrl
    },
    {
      text: 'Shift',
      keyCode: keyCodes.Shift
    },
    {
      text: 'Space',
      keyCode: keyCodes.Space
    },
    {
      text: 'Enter',
      keyCode: keyCodes.Enter
    },
    {
      text: 'Esc',
      keyCode: keyCodes.Esc
    }
  ]
  const udlrBtns = [
    {
      keyCodes: [keyCodes.Up],
      style: {
        transform: 'translate(-50%,0%) rotate(45deg)',
        borderTopLeftRadius: '50em',
        borderBottomLeftRadius: '50em',
        borderTopRightRadius: '50em',
        left: '50%',
        top: '0%',
        width: '40%',
        height: '40%'
      }
    },
    {
      keyCodes: [keyCodes.Left],
      style: {
        transform: 'translate(0%,-50%) rotate(45deg)',
        borderTopLeftRadius: '50em',
        borderBottomRightRadius: '50em',
        borderBottomLeftRadius: '50em',
        left: '0%',
        top: '50%',
        width: '40%',
        height: '40%'
      }
    },
    {
      keyCodes: [keyCodes.Right],
      style: {
        transform: 'translate(-100%,-50%) rotate(45deg)',
        borderTopRightRadius: '50em',
        borderBottomRightRadius: '50em',
        borderTopLeftRadius: '50em',
        left: '100%',
        top: '50%',
        width: '40%',
        height: '40%'
      }
    },
    {
      keyCodes: [keyCodes.Down],
      style: {
        transform: 'translate(-50%,-100%) rotate(45deg)',
        borderTopRightRadius: '50em',
        borderBottomLeftRadius: '50em',
        borderBottomRightRadius: '50em',
        left: '50%',
        top: '100%',
        width: '40%',
        height: '40%'
      }
    },
    {
      keyCodes: [keyCodes.Left, keyCodes.Up],
      style: {
        transform: 'translate(0%,0%)',
        borderBottomLeftRadius: '50em',
        borderTopLeftRadius: '50em',
        borderTopRightRadius: '50em',
        left: '0%',
        top: '0%',
        display: useDir8 ? 'block' : 'none'
      }
    },
    {
      keyCodes: [keyCodes.Left, keyCodes.Down],
      style: {
        transform: 'translate(0%,-100%)',
        borderTopLeftRadius: '50em',
        borderBottomLeftRadius: '50em',
        borderBottomRightRadius: '50em',
        left: '0%',
        top: '100%',
        display: useDir8 ? 'block' : 'none'
      }
    },
    {
      keyCodes: [keyCodes.Right, keyCodes.Up],
      style: {
        transform: 'translate(-100%,0%)',
        borderTopLeftRadius: '50em',
        borderTopRightRadius: '50em',
        borderBottomRightRadius: '50em',
        left: '100%',
        top: '0%',
        display: useDir8 ? 'block' : 'none'
      }
    },
    {
      keyCodes: [keyCodes.Right, keyCodes.Down],
      style: {
        transform: 'translate(-100%,-100%)',
        borderTopRightRadius: '50em',
        borderBottomLeftRadius: '50em',
        borderBottomRightRadius: '50em',
        left: '100%',
        top: '100%',
        display: useDir8 ? 'block' : 'none'
      }
    }
  ]
  const qwzxBtns = [
    {
      text: 'Q',
      keyCode: keyCodes.Q,
      style: {
        transform: 'translate(0%,-50%)',
        left: '0%',
        top: '50%'
      }
    },
    {
      text: 'W',
      keyCode: keyCodes.W,
      style: {
        transform: 'translate(-50%,0%)',
        left: '50%',
        top: '0%'
      }
    },
    {
      text: 'Z',
      keyCode: keyCodes.Z,
      style: {
        transform: 'translate(-50%,-100%)',
        left: '50%',
        top: '100%'
      }
    },
    {
      text: 'X',
      keyCode: keyCodes.X,
      style: {
        transform: 'translate(-100%,-50%)',
        left: '100%',
        top: '50%'
      }
    }
  ]
  const commonStyle = {
    position: 'absolute',
    zIndex: '99999999'
  }
  const padTheme = window.__tyranorTouchPadTheme || {}
  const colorWithAlpha = (value, alpha, fallback) => {
    if (typeof value !== 'string') return fallback
    const hex = value.trim().replace('#', '')
    if (!/^[0-9a-fA-F]{6}$/.test(hex)) return fallback
    const n = parseInt(hex, 16)
    return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${alpha})`
  }
  const padIdleBg = colorWithAlpha(padTheme.primary, 0.38, 'rgba(24,185,120,0.38)')
  const padPressedBg = colorWithAlpha(padTheme.primary, 0.62, 'rgba(24,185,120,0.62)')
  const padTextColor = colorWithAlpha(padTheme.onPrimary, 0.62, 'rgba(255,255,255,0.62)')
  const padShadowColor = colorWithAlpha(padTheme.primary, 0.48, 'rgba(24,185,120,0.48)')
  const padBorderColor = colorWithAlpha(padTheme.onPrimary, 0.18, 'rgba(255,255,255,0.18)')
  const padShadow = `0 0 10px 0 ${padShadowColor}`
  const btnStyle = {
    ...commonStyle,
    background: padIdleBg,
    border: `1px solid ${padBorderColor}`,
    color: padTextColor,
    textAlign: 'center',
    boxShadow: padShadow
  }
  const textStyle = {
    ...commonStyle,
    color: padTextColor,
    transform: 'translate(-50%,-50%)',
    left: '50%',
    top: '50%'
  }
  const switchSize = () => `${padSize * 0.3}px`
  const actionBtnH = () => padSize * 0.125

  /* 全视口布局：始终使用整个屏幕，portrait 下按钮自然落入 letterbox 黑边。 */
  function gameRect() {
    return { rect: { left: 0, top: 0, width: window.innerWidth, height: window.innerHeight } }
  }

  let actionEls = []
  function layout() {
    const g = gameRect()
    const r = g.rect
    padSize = Math.min(r.height * 0.4, r.width * 0.25)
    joyStickSR = padSize * 0.5
    joyStickR = joyStickSR * 0.4
    joyStickCX = r.left + joyStickSR + allMargin + lrMargin
    joyStickCY = r.top + r.height - joyStickSR - allMargin
    const switchTop = (i) => `${r.top + allMargin + i * (padSize * 0.3 + 5)}px`
    Object.assign(keySwitchElement.style, {
      ...btnStyle,
      width: switchSize(),
      height: switchSize(),
      lineHeight: switchSize(),
      borderRadius: '50em',
      left: `${r.left + allMargin}px`,
      top: switchTop(0),
      display: 'block'
    })
    Object.assign(joyStickSwitchElement.style, {
      ...btnStyle,
      width: switchSize(),
      height: switchSize(),
      lineHeight: switchSize(),
      borderRadius: '50em',
      left: `${r.left + allMargin}px`,
      top: switchTop(1),
      display: isKeysShown ? 'block' : 'none'
    })
    Object.assign(dir8SwitchElement.style, {
      ...btnStyle,
      width: switchSize(),
      height: switchSize(),
      lineHeight: switchSize(),
      borderRadius: '50em',
      left: `${r.left + allMargin}px`,
      top: switchTop(2),
      display: isKeysShown ? 'block' : 'none'
    })
    Object.assign(joyStickStage.style, {
      ...commonStyle,
      boxShadow: padShadow,
      width: `${padSize}px`,
      height: `${padSize}px`,
      transform: 'translate(0%,-100%)',
      borderRadius: '50em',
      left: `${r.left + allMargin + lrMargin}px`,
      top: `${joyStickCY + joyStickSR}px`,
      display: useJoyStick && isKeysShown ? 'block' : 'none'
    })
    Object.assign(joyStick.style, {
      ...btnStyle,
      marginLeft: `${joyStickSR - joyStickR}px`,
      marginTop: `${joyStickSR - joyStickR}px`,
      width: `${2 * joyStickR}px`,
      height: `${2 * joyStickR}px`,
      borderRadius: '50em'
    })
    Object.assign(udlrElement.style, {
      ...commonStyle,
      boxShadow: padShadow,
      borderRadius: '50em',
      width: `${padSize}px`,
      height: `${padSize}px`,
      transform: 'translate(0%,-100%)',
      left: `${r.left + allMargin + lrMargin}px`,
      top: `${joyStickCY + joyStickSR}px`,
      display: !useJoyStick && isKeysShown ? 'block' : 'none'
    })
    Object.assign(qwzxElement.style, {
      ...commonStyle,
      width: `${padSize}px`,
      height: `${padSize}px`,
      transform: 'translate(-100%,-100%)',
      borderRadius: '50em',
      boxShadow: padShadow,
      left: `${r.left + r.width - allMargin}px`,
      top: `${r.top + r.height - allMargin}px`,
      display: isKeysShown ? 'block' : 'none'
    })
    const btnW = padSize * 0.5
    const pitch = actionBtnH() + 5
    actionEls.forEach((el, i) => {
      Object.assign(el.style, {
        ...btnStyle,
        width: `${btnW}px`,
        height: `${actionBtnH()}px`,
        lineHeight: `${actionBtnH()}px`,
        borderRadius: '50em',
        right: 'auto',
        left: `${r.left + r.width - allMargin - btnW}px`,
        top: `${r.top + allMargin + i * pitch}px`,
        display: isKeysShown ? 'block' : 'none'
      })
    })
    // 发布动作键列区域，供修改器悬浮球避让（见 __rpgmaker_mod_ui.js）
    window.__touchPadMetrics = {
      actionLeft: r.left + r.width - allMargin - btnW,
      actionTop: r.top + allMargin,
      actionBottom: r.top + allMargin + actionEls.length * pitch - 5
    }
    window.dispatchEvent(new Event('tyranorpadlayout'))
  }

  const setKeyDownColor = (e) => {
    e.style.background = padPressedBg
  }
  const setKeyUpColor = (e) => {
    e.style.background = padIdleBg
  }
  const startKeyEvent = (e, keyCode, keyEvent) => {
    const evtObj = document.createEvent('UIEvents')
    Object.defineProperty(evtObj, 'keyCode', {
      get: () => {
        return evtObj.keyCodeVal
      }
    })
    Object.defineProperty(evtObj, 'which', {
      get: () => {
        return evtObj.keyCodeVal
      }
    })
    evtObj.initUIEvent(keyEvent, true, true, window, 1)
    evtObj.keyCodeVal = keyCode
    e.dispatchEvent(evtObj)
  }
  const setEventStart = (e, keyCodes) => {
    const press = (evt) => {
      evt.stopPropagation()
      evt.preventDefault()
      setKeyDownColor(e)
      keyCodes.forEach(keyCode => {
        startKeyEvent(e, keyCode, 'keydown')
      })
    }
    // 触摸路径 preventDefault 会阻止浏览器合成鼠标事件，不会双触发；
    // 虚拟鼠标（__tyranor_mouse）只派发 MouseEvent，靠这里的 mousedown 命中
    e.addEventListener('touchstart', press)
    e.addEventListener('mousedown', press)
  }
  const setEventMove = (e) => {
    e.addEventListener('touchmove', (evt) => {
      evt.stopPropagation()
      evt.preventDefault()
    })
    e.addEventListener('mousemove', (evt) => { evt.stopPropagation() })
  }
  const setEventEnd = (e, keyCodes) => {
    const release = (evt) => {
      evt.stopPropagation()
      evt.preventDefault()
      setKeyUpColor(e)
      keyCodes.forEach(keyCode => {
        startKeyEvent(e, keyCode, 'keyup')
      })
    }
    e.addEventListener('touchend', release)
    e.addEventListener('mouseup', release)
  }
  const getDistance = (x1, y1, x2, y2) => {
    return Math.sqrt((x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2))
  }
  const getAngle = (x1, y1, x2, y2) => {
    let angle = 180 * Math.atan((y1 - y2) / (x1 - x2)) / Math.PI
    if (x1 >= x2 && y1 < y2) angle += 360
    if (x1 < x2) angle += 180
    return angle
  }
  const endMoveEvent = () => {
    for (const key in udlrEvents) {
      if (udlrEvents[key]) {
        udlrEvents[key] = false
        startKeyEvent(joyStick, keyCodes[key], 'keyup')
      }
    }
  }
  const startMoveEvent = (touch) => {
    if (getDistance(touch.clientX, touch.clientY, joyStickCX, joyStickCY) > 20) {
      const angle = getAngle(touch.clientX, touch.clientY, joyStickCX, joyStickCY)
      const events = useDir8 ? {
        Up: angle > 202.5 && angle < 337.5,
        Right: (angle >= 0 && angle < 67.5) || (angle < 360 && angle > 292.5),
        Down: angle > 22.5 && angle < 157.5,
        Left: angle > 112.5 && angle < 247.5
      } : {
        Up: angle > 225 && angle < 315,
        Right: (angle >= 0 && angle < 45) || (angle < 360 && angle > 315),
        Down: angle > 45 && angle < 135,
        Left: angle > 135 && angle < 225
      }
      for (const key in events) {
        if (events[key] && !udlrEvents[key]) {
          udlrEvents[key] = true
          startKeyEvent(joyStick, keyCodes[key], 'keydown')
        }
        if (!events[key] && udlrEvents[key]) {
          udlrEvents[key] = false
          startKeyEvent(joyStick, keyCodes[key], 'keyup')
        }
      }
    } else {
      endMoveEvent()
    }
  }
  // 开关通用绑定：press/release 与按钮一致走 touchstart/touchend +
  // mousedown/mouseup 双路径（虚拟鼠标靠后者命中）
  const bindSwitch = (el, onRelease) => {
    el.addEventListener('touchstart', (evt) => {
      evt.stopPropagation()
      evt.preventDefault()
      setKeyDownColor(el)
    })
    el.addEventListener('mousedown', (evt) => {
      evt.stopPropagation()
      evt.preventDefault()
      setKeyDownColor(el)
    })
    setEventMove(el)
    const release = (evt) => {
      evt.stopPropagation()
      evt.preventDefault()
      setKeyUpColor(el)
      onRelease()
    }
    el.addEventListener('touchend', release)
    el.addEventListener('mouseup', release)
  }
  bindSwitch(keySwitchElement, () => {
    isKeysShown = !isKeysShown
    keySwitchElement.innerText = isKeysShown ? 'Hide' : 'Show'
    layout()
  })
  bindSwitch(joyStickSwitchElement, () => {
    useJoyStick = !useJoyStick
    joyStickSwitchElement.innerText = useJoyStick ? 'Button' : 'Stick'
    layout()
  })
  bindSwitch(dir8SwitchElement, () => {
    useDir8 = !useDir8
    dir8SwitchElement.innerText = useDir8 ? '4 Dir' : '8 Dir'
    for (let i = 4; i < udlrElement.children.length; i++) {
      udlrElement.children.item(i).style.display = useDir8 ? 'block' : 'none'
    }
  })
  const joyStart = (x, y) => {
    joyStick.style.left = `${x - joyStickCX}px`
    joyStick.style.top = `${y - joyStickCY}px`
    startMoveEvent({ clientX: x, clientY: y })
  }
  const joyMove = (x, y) => {
    const subLen = getDistance(x, y, joyStickCX, joyStickCY)
    if (subLen > joyStickSR) {
      joyStick.style.left = `${(x - joyStickCX) * joyStickSR / subLen}px`
      joyStick.style.top = `${(y - joyStickCY) * joyStickSR / subLen}px`
    } else {
      joyStick.style.left = `${x - joyStickCX}px`
      joyStick.style.top = `${y - joyStickCY}px`
    }
    startMoveEvent({ clientX: x, clientY: y })
  }
  const joyEnd = () => {
    joyStick.style.left = '0px'
    joyStick.style.top = '0px'
    endMoveEvent()
  }
  joyStickStage.addEventListener('touchstart', (evt) => {
    evt.stopPropagation()
    evt.preventDefault()
    const touch = evt.targetTouches[0]
    joyStart(touch.clientX, touch.clientY)
  })
  joyStickStage.addEventListener('touchmove', (evt) => {
    evt.stopPropagation()
    evt.preventDefault()
    const touch = evt.targetTouches[0]
    joyMove(touch.clientX, touch.clientY)
  })
  joyStickStage.addEventListener('touchend', (evt) => {
    evt.stopPropagation()
    evt.preventDefault()
    joyEnd()
  })
  // 虚拟鼠标拖拽摇杆：光标按下后跟随 mousemove
  let joyMouseDown = false
  joyStickStage.addEventListener('mousedown', (evt) => {
    evt.stopPropagation()
    evt.preventDefault()
    joyMouseDown = true
    joyStart(evt.clientX, evt.clientY)
  })
  window.addEventListener('mousemove', (evt) => {
    if (!joyMouseDown) return
    evt.stopPropagation()
    joyMove(evt.clientX, evt.clientY)
  })
  window.addEventListener('mouseup', (evt) => {
    if (!joyMouseDown) return
    evt.stopPropagation()
    joyMouseDown = false
    joyEnd()
  })
  actionsBtns.forEach(it => {
    const childElement = document.createElement('div')
    actionsElement.appendChild(childElement)
    childElement.innerText = it.text
    actionEls.push(childElement)
    setEventStart(childElement, [it.keyCode])
    setEventMove(childElement)
    setEventEnd(childElement, [it.keyCode])
  })
  udlrBtns.forEach(it => {
    const childElement = document.createElement('div')
    udlrElement.appendChild(childElement)
    Object.assign(childElement.style, {
      ...btnStyle,
      width: '33%',
      height: '33%',
      ...it.style
    })
    setEventStart(childElement, it.keyCodes)
    setEventMove(childElement)
    setEventEnd(childElement, it.keyCodes)
  })
  qwzxBtns.forEach(it => {
    const childElement = document.createElement('div')
    qwzxElement.appendChild(childElement)
    Object.assign(childElement.style, {
      ...btnStyle,
      width: '40%',
      height: '40%',
      borderRadius: '50em',
      ...it.style
    })
    setEventStart(childElement, [it.keyCode])
    setEventMove(childElement)
    setEventEnd(childElement, [it.keyCode])
    const tElement = document.createElement('div')
    childElement.appendChild(tElement)
    Object.assign(tElement.style, textStyle)
    tElement.innerText = it.text
  })
  layout()
  window.addEventListener('resize', layout)
  // 旋转后画布矩形可能滞后于视口变化，延迟一帧再排
  window.addEventListener('orientationchange', () => setTimeout(layout, 150))
  }

  if (document.readyState === 'loading') {
    window.addEventListener('load', initTouchPad, { once: true })
  } else {
    initTouchPad()
  }
})()
