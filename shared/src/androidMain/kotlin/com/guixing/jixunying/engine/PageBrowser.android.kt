package com.guixing.jixunying.engine

import java.io.File

/** 手机上还没有（读平台链接只在电脑上做；手机遥控电脑时由电脑去读）。 */
actual fun createPageBrowser(profileDir: File): PageBrowser? = null
