package com.github.halfdeadgames.hdgife.ktx

import com.github.halfdeadgames.hdgife.HdgifeLog
import ktx.log.Logger

public fun hdgifeLog(logger: Logger = Logger("hdgife")): HdgifeLog = HdgifeLog { message -> logger.info { message } }
