package com.lightning.walletapp

import spray.json._
import com.lightning.walletapp.ln._
import com.lightning.walletapp.Utils._
import com.lightning.walletapp.ln.wire._
import com.lightning.walletapp.R.string._
import com.lightning.walletapp.ln.Tools._
import com.lightning.walletapp.ln.Channel._
import com.lightning.walletapp.Denomination._
import com.lightning.walletapp.lnutils.ImplicitConversions._
import com.lightning.walletapp.lnutils.ImplicitJsonFormats._
import android.widget.{ImageButton, TextView}
import scala.util.{Success, Try}

import com.lightning.walletapp.lnutils.olympus.ChannelUploadAct
import com.lightning.walletapp.ln.Scripts.pubKeyScript
import com.lightning.walletapp.helper.AES
import fr.acinq.bitcoin.Crypto.PublicKey
import org.bitcoinj.script.ScriptBuilder
import org.bitcoinj.wallet.SendRequest
import fr.acinq.bitcoin.MilliSatoshi
import android.app.AlertDialog
import org.bitcoinj.core.Batch
import scodec.bits.ByteVector
import android.os.Bundle


class LNStartFundActivity extends TimerActivity { me =>
  lazy val lnStartFundCancel = findViewById(R.id.lnStartFundCancel).asInstanceOf[ImageButton]
  lazy val lnStartFundDetails = findViewById(R.id.lnStartFundDetails).asInstanceOf[TextView]
  lazy val fundNodeView = app getString ln_ops_start_fund_node_view
  var whenBackPressed: Runnable = UITask(super.onBackPressed)
  override def onBackPressed = whenBackPressed.run

  def INIT(s: Bundle) = if (app.isAlive) {
    setContentView(R.layout.activity_ln_start_fund)
    defineFurtherActionBasedOnTransDataValue
  } else me exitTo classOf[MainActivity]

  def defineFurtherActionBasedOnTransDataValue = app.TransData checkAndMaybeErase {
    case data @ RemoteNodeView(ann \ _) => proceed(Left(Nil), data.asString(fundNodeView), ann)
    case data @ HardcodedNodeView(ann, _) => proceed(Left(Nil), data.asString(fundNodeView), ann)
    case data: NodeAnnouncement => proceed(Left(Nil), HardcodedNodeView(data, tip = "( ͡° ͜ʖ ͡°)").asString(fundNodeView), data)
    case data: IncomingChannelParams => proceed(Left(data.open :: Nil), data.nodeView.asString(fundNodeView), data.nodeView.ann)
    case data: HostedChannelRequest => proceed(Right(data.secret), data.asString(fundNodeView), data.ann)
    case _ => finish
  }

  def finalizeSetup(chan: Channel) = {
    // Make this channel able to receive ordinary events now
    // additionally this will implicitly remove an OpenListener
    chan.listeners = ChannelManager.operationalListeners
    ChannelManager.all +:= chan

    // Tell wallet activity to redirect to ops
    app.TransData.value = FragWallet.REDIRECT
    me exitTo MainActivity.wallet
  }

  def saveNormalChannel(chan: NormalChannel, some: HasNormalCommits) = {
    // Saving error will halt all further progress, this is exactly desired
    chan STORE some

    app.kit.wallet.addWatchedScripts(app.kit fundingPubScript some)
    // Start watching a channel funding script and save a channel, order an encrypted backup upload
    val encrypted = AES.encReadable(RefundingData(some.announce, None, some.commitments).toJson.toString, LNParams.cloudSecret.toArray)
    val chanUpload = ChannelUploadAct(encrypted.toByteVector, Seq("key" -> LNParams.cloudId.toHex), "data/put", some.announce.alias)
    app.olympus.tellClouds(chanUpload)
    finalizeSetup(chan)
  }

  def proceed(mode: Either[List[OpenChannel], ByteVector], asString: String, ann: NodeAnnouncement): Unit = {
    val walletPubKeyScript = ByteVector(ScriptBuilder.createOutputScript(app.kit.currentAddress).getProgram)

    val safeAddressOrAlias = Try(ann.unsafeFirstAddress.get.toString).toOption getOrElse ann.alias
    val peerOffline = new LightningException(me getString err_ln_peer_offline format safeAddressOrAlias)
    val peerIncompatible = new LightningException(me getString err_ln_peer_incompatible format ann.alias)
    val chainNotConnectedYet = new LightningException(me getString err_ln_chain_disconnected)
    val chanExistsAlready = new LightningException(me getString err_ln_chan_exists_already)
    lnStartFundCancel setOnClickListener onButtonTap(whenBackPressed.run)
    lnStartFundDetails setText asString.html

    abstract class OpenListener[T <: Channel] extends ConnectionListener with ChannelListener {
      // Whenever anythig goes wrong we just disconnect and shut activity down since we risk nothing at opening phase
      override def onDisconnect(nodeId: PublicKey) = if (nodeId == ann.nodeId) onException(freshChannel -> peerOffline)

      override def onMessage(nodeId: PublicKey, message: LightningMessage) = message match {
        case remoteError: Error if nodeId == ann.nodeId => onException(freshChannel -> remoteError.exception)
        case open: OpenChannel if nodeId == ann.nodeId && !open.channelFlags.isPublic => onOpenOffer(nodeId, open)
        case _: ChannelSetupMessage if nodeId == ann.nodeId => freshChannel process message
        case _ => // We only listen to setup messages here to avoid conflicts
      }

      override def onOpenOffer(nodeId: PublicKey, open: OpenChannel) = {
        val incomingOpeningTip = app getString ln_ops_start_fund_incoming_channel
        val hnv = HardcodedNodeView(ann, incomingOpeningTip)

        // This one is useless now
        freshChannel.listeners -= this
        ConnectionManager.listeners -= this
        // Replace outgoing listener with an incoming one
        app.TransData.value = IncomingChannelParams(hnv, open)
        UITask(defineFurtherActionBasedOnTransDataValue).run
      }

      override def onException = {
        case (_: Channel, openingError) =>
          // Cancel everything in case of local/remote failure
          UITask(app quickToast openingError.getMessage).run
          whenBackPressed.run
      }

      val freshChannel: T
    }

    class LocalOpenListener extends OpenListener[NormalChannel] {
      val freshChannel = ChannelManager.createChannel(Set.empty, InitData apply ann)
      override def onOperational(nodeId: PublicKey, isCompat: Boolean) = if (nodeId == ann.nodeId) {
        // Peer has sent us their Init so we ask user to provide a funding amount if peer is compatible
        if (ChannelManager hasNormalChanWith nodeId) onException(freshChannel -> chanExistsAlready)
        else if (!isCompat) onException(freshChannel -> peerIncompatible)
        else askLocalFundingConfirm.run
      }

      override def onBecome = {
        case (_: NormalChannel, _, _, WAIT_FOR_FUNDING | WAIT_FUNDING_DONE) if ChannelManager.currentBlocksLeft.isEmpty =>
          // Funding with unreachable on-chain peers is problematic, just cancel the whole operation and let user know
          onException(freshChannel -> chainNotConnectedYet)

        case (_: NormalChannel, WaitFundingData(_, cmd, accept), WAIT_FOR_ACCEPT, WAIT_FOR_FUNDING) =>
          // We create a funding transaction by replacing an output with a real one in a saved dummy funding transaction
          val req = cmd.batch replaceDummy pubKeyScript(cmd.localParams.fundingPrivKey.publicKey, accept.fundingPubkey)
          freshChannel process CMDFunding(app.kit.sign(req).tx)

        case transition @ (_: NormalChannel, wait: WaitFundingDoneData, WAIT_FUNDING_SIGNED, WAIT_FUNDING_DONE) =>
          // Preliminary negotiations are complete, save channel FIRST AND THEN broadcast a funding transaction
          saveNormalChannel(freshChannel, wait)
          ConnectionManager.listeners -= this
          ChannelManager onBecome transition
      }

      def makeTxProcessor(ms: MilliSatoshi) = new TxProcessor {
        // A popup which offers user to select a funding on-chain fee, shuts activity down if anything goes wrong along the way
        def onTxFail(error: Throwable): Unit = mkCheckForm(alert => rm(alert)(finish), none, txMakeErrorBuilder(error), dialog_ok, -1)

        def futureProcess(unsignedRequest: SendRequest) = {
          val batch = Batch(unsignedRequest, dummyScript, null)
          val theirReserveImposedByUsSat = batch.fundingAmountSat / LNParams.channelReserveToFundingRatio
          val localParams = LNParams.makeLocalParams(ann, theirReserveImposedByUsSat, walletPubKeyScript, randomPrivKey, isFunder = true)
          val cmd = CMDOpenChannel(localParams, ByteVector(random getBytes 32), LNParams.broadcaster.perKwThreeSat, batch, batch.fundingAmountSat)
          freshChannel process cmd
        }

        val dummyKey = randomPrivKey.publicKey
        val dummyScript = pubKeyScript(dummyKey, dummyKey)
        val pay = P2WSHData(ms, pay2wsh = dummyScript)
      }

      def askLocalFundingConfirm = UITask {
        val content = getLayoutInflater.inflate(R.layout.frag_input_fiat_converter, null, false)
        val maxCap = MilliSatoshi(LNParams.maxCapacity.amount min app.kit.conf0Balance.value * 1000L)
        val minCap = MilliSatoshi(LNParams.minCapacityMsat max LNParams.broadcaster.perKwThreeSat * 3 * 1000L)
        val rateManager = new RateManager(content) hint getString(amount_hint_newchan).format(denom parsedWithSign minCap,
          denom parsedWithSign LNParams.maxCapacity, denom parsedWithSign app.kit.conf0Balance)

        def askAttempt(alert: AlertDialog) = rateManager.result match {
          case Success(ms) if ms < minCap => app quickToast dialog_sum_small
          case Success(ms) if ms > maxCap => app quickToast dialog_sum_big

          case Success(ms) =>
            val txProcessor = makeTxProcessor(ms)
            val coloredAmount = denom.coloredP2WSH(txProcessor.pay.cn, denom.sign)
            val coloredExplanation = txProcessor.pay destination coloredAmount
            rm(alert)(txProcessor start coloredExplanation)

          // No amount has been entered at all
          case _ => app quickToast dialog_sum_small
        }

        def useMax(alert: AlertDialog) = rateManager setSum Try(maxCap)
        val bld = baseBuilder(getString(ln_ops_start_fund_local_title).html, content)
        mkCheckFormNeutral(askAttempt, none, useMax, bld, dialog_next, dialog_cancel, dialog_max)
      }
    }

    class RemoteOpenListener(open: OpenChannel) extends OpenListener[NormalChannel] {
      val theirReserveImposedByUsSat = open.fundingSatoshis / LNParams.channelReserveToFundingRatio
      val params = LNParams.makeLocalParams(ann, theirReserveImposedByUsSat, walletPubKeyScript, randomPrivKey, isFunder = false)
      // We are already connected to remote peer at this point so proceed with replying to their request right away
      val freshChannel = ChannelManager.createChannel(Set.empty, InitData apply ann)
      freshChannel process Tuple2(params, open)

      override def onBecome = {
        case (_: NormalChannel, wait: WaitBroadcastRemoteData, WAIT_FOR_FUNDING, WAIT_FUNDING_DONE) =>
          // Preliminary negotiations are complete, save channel and wait for their funding tx
          saveNormalChannel(freshChannel, wait)
          ConnectionManager.listeners -= this
      }
    }

    class HostedClientOpenListener(secret: ByteVector) extends OpenListener[HostedChannel] {
      val waitData = WaitRemoteHostedReply(ann, refundScriptPubKey = walletPubKeyScript, secret)
      val freshChannel = ChannelManager.createHostedChannel(Set.empty, waitData)

      override def onBecome = {
        case (chan: HostedChannel, _, WAIT_FOR_ACCEPT, OPEN) =>
          // Hosted channel is now established and stored
          ConnectionManager.listeners -= this
          finalizeSetup(chan)
      }

      override def onOperational(nodeId: PublicKey, isCompat: Boolean) = if (nodeId == ann.nodeId) {
        if (ChannelManager.currentBlocksLeft.isEmpty) onException(freshChannel -> chainNotConnectedYet)
        else if (ChannelManager hasHostedChanWith nodeId) onException(freshChannel -> chanExistsAlready)
        else if (!isCompat) onException(freshChannel -> peerIncompatible)
        else freshChannel.startUp
      }

      override def onHostedMessage(ann1: NodeAnnouncement, message: HostedChannelMessage) =
        // At this point hosted channel can only receive hosted messages or Error
        if (ann.nodeId == ann1.nodeId) freshChannel process message

      // We override this once again to treat possibly tagged remote Error differently
      override def onMessage(nodeId: PublicKey, message: LightningMessage) = message match {
        case remoteError: Error if nodeId == ann.nodeId => translateHostedTaggedError(remoteError)
        case _ => super.onMessage(nodeId, message)
      }

      def translateHostedTaggedError(error: Error) = {
        val errMessage = ChanErrorCodes.translateTag(error)
        onException(freshChannel -> errMessage)
      }
    }

    val openListener = mode match {
      case Right(secret) => new HostedClientOpenListener(secret)
      case Left(open :: _) => new RemoteOpenListener(open)
      case _ => new LocalOpenListener
    }

    whenBackPressed = UITask {
      ConnectionManager.listeners -= openListener
      openListener.freshChannel.listeners -= openListener
      // Worker may have already been removed on some connection failure
      // we need to disconnect here to not accumulate too many open sockets
      ConnectionManager.workers.get(ann.nodeId).foreach(_.disconnect)
      finish
    }

    ConnectionManager.listeners += openListener
    openListener.freshChannel.listeners += openListener
    ConnectionManager.connectTo(ann, notify = true)
  }
}