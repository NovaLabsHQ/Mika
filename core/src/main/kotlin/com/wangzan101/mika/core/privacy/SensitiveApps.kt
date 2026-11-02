package com.wangzan101.mika.core.privacy

/**
 * 默认的隐私名单:银行、金融、券商、加密货币、健康、医疗、密码管理器和验证器一类的 App。名单上的 App Mika 一概不碰:
 * 不打开、不看、不操作(和「别动这些 App」不同,那个只是不挪它)。用户可以在设置 › 隐私 ›「敏感 App」里逐个放开,
 * 或另加(见 [SensitiveAppPolicy])。
 *
 * 判法(全是数据,改这里就行):
 * 1. [curated]:点名的包 → 类别。各大银行、支付、券商、交易所、验证器、密码管理器、病历 App 的正式包名。
 * 2. [keywords]:包名按 `.`、`_`、`-` 切成段,有一段**整段等于**关键词就算(不做子串匹配:`bankruptcy`、`healthyrecipes`
 *    不会因为含 bank、health 被当成敏感 App)。
 * 3. [neverBlock]:从不算的包(Mika 自己)。优先于上面两条。
 *
 * 要加:点名的往 [curated] 里加一行;一类 App 的包名普遍带某个词的,往 [keywords] 里加(先想清楚会不会误伤别的 App,
 * 并在 SensitiveAppsTest 的反例里补一条)。纯数据加纯逻辑,不依赖 Android。
 */
object SensitiveApps {
    /** 类别:设置页按它分组、写明为什么在名单上。 */
    enum class Category { Banking, Finance, Brokerage, Crypto, Health, Medical, Passwords, Authenticator }

    /** 为什么在名单上:点名的([Curated]),还是包名里有关键词([Keyword])。 */
    enum class Reason { Curated, Keyword }

    /** [packageName] 在名单上:[category] 类别,[reason] 依据,[keyword] 按关键词判时是哪个词。 */
    data class Match(val packageName: String, val category: Category, val reason: Reason, val keyword: String? = null)

    /** 点名的包。 */
    val curated: Map<String, Category> = buildMap {
        // 银行
        for (p in listOf(
            "com.chase.sig.android", "com.infonow.bofa", "com.wf.wellsfargomobile", "com.citi.citimobile",
            "com.usbank.mobilebanking", "com.konylabs.capitalone", "com.capitalone.mobile", "com.pnc.ecommerce.mobile",
            "com.td", "com.tdbank", "com.ally.MobileBanking", "com.discoverfinancial.mobile", "com.americanexpress.android.acctsvcs.us",
            "com.sofi.mobile", "com.chime.chmapplication", "com.varomoney.bank", "com.barclays.android.barclaysmobilebanking",
            "uk.co.hsbc.hsbcukmobilebanking", "com.grppl.android.shell.CMBlloydsTSB73", "com.rbs.mobile.android.natwest",
            "com.monzo.android", "com.revolut.revolut", "com.starlingbank.android", "com.n26.android", "de.number26.android",
            "com.ing.mobile", "com.commbank.netbank", "au.com.nab.mobile", "org.westpac.bank", "com.anz.android.gomoney",
            "com.rbc.mobile.android", "com.bmo.mobile", "com.cibc.android.mobi", "com.scotiabank.banking",
            "cmb.pb", "com.chinamworld.main", "com.icbc", "com.ccb.ccbnetpay", "com.android.bankabc", "com.bankcomm.Bankcomm",
            "com.hdfcbank.payzapp", "com.snapwork.hdfc", "com.sbi.lotusintouch", "com.csam.icici.bank.imobile", "com.axis.mobile",
        )) put(p, Category.Banking)
        // 支付、钱包、记账、报税
        for (p in listOf(
            "com.paypal.android.p2pmobile", "com.venmo", "com.squareup.cash", "com.zellepay.zelle", "com.google.android.apps.walletnfcrel",
            "com.google.android.apps.nbu.paisa.user", "com.samsung.android.spay", "com.samsung.android.samsungpay.gear",
            "com.eg.android.AlipayGphone", "com.alipay.mobile.client", "com.phonepe.app", "net.one97.paytm", "com.wise.android",
            "com.transferwise.android", "com.klarna.android", "com.affirm.central", "com.afterpay.caportal", "com.mint", "com.intuit.mint",
            "com.creditkarma.mobile", "com.intuit.turbotax.mobile", "com.intuit.quickbooks", "com.ynab.evergreen.app", "com.experian.android",
            "com.westernunion.moneytransferr3app.us", "com.moneygram.mobile",
        )) put(p, Category.Finance)
        // 券商、投资、退休金
        for (p in listOf(
            "com.robinhood.android", "com.fidelity.android", "com.schwab.mobile", "com.etrade.mobilepro.activity", "com.vanguard",
            "com.tdameritrade.mobile3", "com.webull.trade", "com.webull.android", "com.wealthfront", "com.betterment", "com.acorns.android",
            "com.stash.stashinvest", "com.ibkr.mobile", "atws.app", "com.sofi.invest", "com.public.android", "com.merrilledge.mobile",
            "com.ml.mobile.mymerrill", "com.morganstanley.clientmobile.prod", "com.tradingview.tradingviewapp", "com.futu.moomoo",
            "com.moomoo.trade", "com.tigerbrokers.stock", "com.plus500", "com.etoro.openbook", "com.trading212.trading212",
        )) put(p, Category.Brokerage)
        // 加密货币
        for (p in listOf(
            "com.coinbase.android", "org.toshi", "com.binance.dev", "com.binance.us", "com.krakenfutures", "com.kraken.trade",
            "com.kraken.invest.app", "co.mona.android", "com.crypto.exchange", "piuk.blockchain.android", "com.wallet.crypto.trustapp",
            "io.metamask", "com.gemini.android.app", "com.bitstamp.app", "com.okinc.okex.gp", "com.bybit.app", "io.exodus.wallet",
            "com.ledger.live", "app.phantom", "com.kucoin.app",
        )) put(p, Category.Crypto)
        // 健康
        for (p in listOf(
            "com.google.android.apps.healthdata", "com.google.android.apps.fitness", "com.sec.android.app.shealth", "com.fitbit.FitbitMobile",
            "com.myfitnesspal.android", "com.flo.health", "com.clue.android", "org.iggymedia.periodtracker", "com.ovuline.fertility",
            "com.headspace.android", "com.calm.android", "com.betterhelp", "com.talkspace.talkspaceapp", "com.ouraring.oura",
            "com.whoop.android", "com.withings.wiscale2", "com.huawei.health", "com.xiaomi.wearable", "com.mi.health",
        )) put(p, Category.Health)
        // 医疗、病历、药房、保险
        for (p in listOf(
            "epic.mychart.android", "com.teladoc.members", "com.americanwell.android.member.amwell", "com.zocdoc.android",
            "com.kp.m", "org.kp.m", "com.cvs.launchers.cvs", "com.walgreens.android", "com.goodrx", "com.uhc.mobile",
            "com.anthem.sydney", "com.aetna.aetnahealth", "com.cigna.mobile.mycigna", "com.bcbs.mobile", "com.medisafe.android.client",
            "com.onemedical.android", "com.healow", "com.followmyhealth.android", "com.nhs.online.nhsonline", "uk.nhs.nhsapp",
            "com.babylon.patient", "com.doctolib.android", "com.practo.fabric",
        )) put(p, Category.Medical)
        // 密码管理器
        for (p in listOf(
            "com.x8bit.bitwarden", "com.bitwarden.authenticator", "com.agilebits.onepassword", "com.lastpass.lpandroid",
            "com.dashlane", "com.keepersecurity.keeper", "com.callpod.android_apps.keeper", "com.samsung.android.samsungpass",
            "keepass2android.keepass2android", "keepass2android.keepass2android_nonet",
            "com.kunzisoft.keepass.free", "com.kunzisoft.keepass.libre", "proton.android.pass", "com.nordpass.android.app.password.manager",
            "com.enpass.android", "com.roboform.android", "com.siber.roboform", "com.zoho.vault",
        )) put(p, Category.Passwords)
        // 验证器
        for (p in listOf(
            "com.google.android.apps.authenticator2", "com.azure.authenticator", "com.authy.authy", "com.twilio.authy",
            "com.duosecurity.duomobile", "org.fedorahosted.freeotp", "com.beemdevelopment.aegis", "com.yubico.yubioath",
            "com.okta.android.auth", "com.lastpass.authenticator", "com.salesforce.authenticator", "com.entrust.identityGuard.mobile",
            "com.rsa.securidapp", "com.symantec.mobile.idsafe", "com.vmware.authenticator", "com.ente.auth", "io.ente.auth",
            "com.authenticator.authservice2", "com.microsoft.msa.authenticator",
        )) put(p, Category.Authenticator)
    }

    /**
     * 关键词 → 类别:包名有一段整段等于它就算。合写的一段(`mobilebanking`、`onlinebanking`)单独列出,
     * 不做子串匹配,免得误伤。
     */
    val keywords: Map<String, Category> = buildMap {
        for (k in listOf("bank", "banking", "mobilebanking", "onlinebanking", "netbanking", "ebanking", "ibanking", "mybank", "bankapp", "creditunion"))
            put(k, Category.Banking)
        for (k in listOf("wallet", "paywallet", "payments", "fintech", "moneytransfer", "remittance", "creditcard", "loans"))
            put(k, Category.Finance)
        for (k in listOf("brokerage", "broker", "stocks", "stocktrading", "trading", "invest", "investing", "securities"))
            put(k, Category.Brokerage)
        for (k in listOf("crypto", "cryptowallet", "bitcoin", "btc", "ethereum", "blockchain", "coinbase", "binance", "defi"))
            put(k, Category.Crypto)
        for (k in listOf("health", "healthcare", "shealth", "telehealth", "fertility", "periodtracker"))
            put(k, Category.Health)
        for (k in listOf("medical", "medicine", "pharmacy", "patient", "patientportal", "mychart", "hospital", "clinic", "doctor", "telemedicine"))
            put(k, Category.Medical)
        for (k in listOf("password", "passwords", "passwordmanager", "1password", "onepassword", "bitwarden", "lastpass", "dashlane", "keepass", "keepassdx", "vault"))
            put(k, Category.Passwords)
        for (k in listOf("authenticator", "authenticator2", "authy", "otp", "totp", "2fa", "mfa"))
            put(k, Category.Authenticator)
    }

    /** 从不算的包(Mika 自己,任何版本)。 */
    private val neverBlockPrefixes = listOf("com.wangzan101.mika")

    /** [packageName] 从不算敏感 App。 */
    fun neverBlock(packageName: String): Boolean =
        neverBlockPrefixes.any { packageName == it || packageName.startsWith("$it.") || packageName.startsWith("${it}_") }

    private val SEPARATORS = Regex("[._-]")

    /** [packageName] 在默认名单上为匹配结果,不在为 null。大小写不敏感。 */
    fun match(packageName: String): Match? {
        if (packageName.isBlank() || neverBlock(packageName)) return null
        curated[packageName]?.let { return Match(packageName, it, Reason.Curated) }
        curated.entries.firstOrNull { it.key.equals(packageName, ignoreCase = true) }?.let { return Match(packageName, it.value, Reason.Curated) }
        for (seg in packageName.lowercase().split(SEPARATORS)) {
            keywords[seg]?.let { return Match(packageName, it, Reason.Keyword, seg) }
        }
        return null
    }
}
