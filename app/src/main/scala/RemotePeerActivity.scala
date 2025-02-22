package wtf.nbd.obw

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success}
import android.os.Bundle
import android.view.View
import android.widget.{LinearLayout, TextView}
import androidx.appcompat.app.AlertDialog
import com.ornach.nobobutton.NoboButton
import org.apmem.tools.layouts.FlowLayout
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin._
import fr.acinq.eclair.Features._
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.electrum.ElectrumEclairWallet
import fr.acinq.eclair.blockchain.electrum.ElectrumWallet.GenerateTxResponse
import fr.acinq.eclair.blockchain.fee.{FeeratePerByte, FeeratePerKw}
import fr.acinq.eclair.channel.{
  Commitments,
  DATA_WAIT_FOR_FUNDING_CONFIRMED,
  DATA_WAIT_FOR_FUNDING_INTERNAL
}
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire._
import immortan._
import immortan.crypto.Tools._
import immortan.fsm.{HCOpenHandler, NCFundeeOpenHandler, NCFunderOpenHandler}
import immortan.utils._
import wtf.nbd.obw.BaseActivity.StringOps
import wtf.nbd.obw.R
import wtf.nbd.obw.utils.{firstLast}

object RemotePeerActivity {
  def implantNewChannel(cs: Commitments, freshChannel: Channel): Unit = {
    // Make an immediate channel backup if anything goes wrong next
    // At this point channel has saved itself in the database
    WalletApp.immediatelySaveBackup()

    LNParams.cm.pf.process(PathFinder.CMDStartPeriodicResync)
    LNParams.cm.all += Tuple2(cs.channelId, freshChannel)
    // This removes all previous channel listeners
    freshChannel.listeners = Set(LNParams.cm)
    LNParams.cm.initConnect()

    // Update view on hub activity and finalize local stuff
    ChannelMaster.next(ChannelMaster.statusUpdateStream)
  }
}

class RemotePeerActivity
    extends ChanErrorHandlerActivity
    with ExternalDataChecker {
  private[this] lazy val peerNodeKey =
    findViewById(R.id.peerNodeKey).asInstanceOf[TextView]
  private[this] lazy val peerIpAddress =
    findViewById(R.id.peerIpAddress).asInstanceOf[TextView]
  private[this] lazy val titleText =
    findViewById(R.id.titleText).asInstanceOf[TextView]

  private[this] lazy val featuresList =
    findViewById(R.id.featuresList).asInstanceOf[FlowLayout]
  private[this] lazy val viewNoFeatureSupport = findViewById(
    R.id.viewNoFeatureSupport
  ).asInstanceOf[TextView]
  private[this] lazy val viewYesFeatureSupport = findViewById(
    R.id.viewYesFeatureSupport
  ).asInstanceOf[LinearLayout]
  private[this] lazy val optionHostedChannel = findViewById(
    R.id.optionHostedChannel
  ).asInstanceOf[NoboButton]

  private[this] lazy val criticalFeatures =
    Set(BasicMultiPartPayment, DataLossProtect, StaticRemoteKey)

  private[this] lazy val featureTextViewMap = Map(
    ChannelRangeQueriesExtended -> findViewById(
      R.id.ChannelRangeQueriesExtended
    ).asInstanceOf[TextView],
    BasicMultiPartPayment -> findViewById(R.id.BasicMultiPartPayment)
      .asInstanceOf[TextView],
    DataLossProtect -> findViewById(R.id.OptionDataLossProtect)
      .asInstanceOf[TextView],
    StaticRemoteKey -> findViewById(R.id.StaticRemoteKey)
      .asInstanceOf[TextView],
    HostedChannels -> findViewById(R.id.HostedChannels).asInstanceOf[TextView],
    Wumbo -> findViewById(R.id.Wumbo).asInstanceOf[TextView]
  )

  class DisconnectListener extends ConnectionListener {
    override def onDisconnect(worker: CommsTower.Worker): Unit = {
      UITask(WalletApp.app.quickToast(R.string.rpa_disconnected)).run
      disconnectListenersAndFinish()
    }
  }

  private lazy val incomingAcceptingListener = new DisconnectListener {
    override def onMessage(
        worker: CommsTower.Worker,
        message: LightningMessage
    ): Unit = message match {
      case theirMsg: ChannelReestablish
          if !LNParams.cm.all.contains(theirMsg.channelId) =>
        chanUnknown(worker, theirMsg)
      case theirMsg: OpenChannel if (theirMsg.channelFlags & 0x01) == 0 =>
        acceptIncomingChannel(theirMsg)
      case _: OpenChannel =>
        UITask(
          WalletApp.app.quickToast(R.string.error_rejected_incoming_public)
        ).run
      case _ => // Do nothing
    }
  }

  private lazy val incomingIgnoringListener = new DisconnectListener

  private var criticalSupportAvailable: Boolean = false
  private var whenBackPressed: Runnable = UITask(finish)
  private var hasInfo: HasRemoteInfo = _

  private lazy val viewUpdatingListener = new ConnectionListener {
    override def onOperational(
        worker: CommsTower.Worker,
        theirInit: Init
    ): Unit = UITask {
      val theirInitSupports: Feature with InitFeature => Boolean =
        LNParams.isPeerSupports(theirInit)

      criticalSupportAvailable = criticalFeatures.forall(theirInitSupports)

      featureTextViewMap foreach {
        case (feature, view) if theirInitSupports(feature) =>
          view.setBackgroundResource(R.drawable.border_green)
          view.setText(feature.rfcName)

        case (feature, view) if criticalFeatures.contains(feature) =>
          view.setBackgroundResource(R.drawable.border_red)
          view.setText(feature.rfcName)

        case (feature, view) =>
          view.setBackgroundResource(R.drawable.border_basic)
          view.setText(feature.rfcName)
      }

      hasInfo match {
        case nc: NormalChannelRequest if criticalSupportAvailable =>
          nc.requestChannel.foreach(none, revertAndInform)
        case hc: HostedChannelRequest
            if criticalSupportAvailable && theirInitSupports(HostedChannels) =>
          askHostedChannel(hc.secret)
        case _: HasRemoteInfoWrap =>
          setVis(isVisible = criticalSupportAvailable, viewYesFeatureSupport)
        case _ => whenBackPressed.run
      }

      setVis(isVisible = !criticalSupportAvailable, viewNoFeatureSupport)
      setVis(
        isVisible = theirInitSupports(HostedChannels),
        optionHostedChannel
      )
      setVis(isVisible = true, featuresList)
    }.run
  }

  def activateInfo(info: HasRemoteInfo): Unit = {
    peerNodeKey.setText(info.remoteInfo.nodeId.toString.take(16).humanFour)
    peerIpAddress.setText(info.remoteInfo.address.toString)
    hasInfo = info

    whenBackPressed = UITask {
      // Disconnect and clear up if anything goes wrong
      CommsTower.disconnectNative(info.remoteInfo)
      info.cancel()
    }

    // Try to connect after assigning vars since listener may fire immediately
    val listeners = Set(viewUpdatingListener, incomingAcceptingListener)
    CommsTower.listenNative(listeners, info.remoteInfo)
  }

  override def onBackPressed(): Unit = whenBackPressed.run
  override def checkExternalData(whenNone: Runnable): Unit =
    InputParser.checkAndMaybeErase {
      case remoteInfo: RemoteNodeInfo =>
        activateInfo(HasRemoteInfoWrap(remoteInfo))
      case hasRemoteInfo: HasRemoteInfo => activateInfo(hasRemoteInfo)
      case _                            => whenNone.run
    }

  override def PROCEED(state: Bundle): Unit = {
    setContentView(R.layout.activity_remote_peer)
    checkExternalData(whenBackPressed)
    titleText.setText(getString(R.string.rpa_title))
  }

  // BUTTON ACTIONS
  def acceptIncomingChannel(theirOpen: OpenChannel): Unit = {
    new NCFundeeOpenHandler(hasInfo.remoteInfo, theirOpen, LNParams.cm) {
      override def onEstablished(cs: Commitments, chan: ChannelNormal): Unit =
        implant(cs, chan)
      override def onFailure(reason: Throwable): Unit = revertAndInform(reason)
      stopAcceptingIncomingOffers()
    }
  }

  def doFundNewChannel(fromWallet: ElectrumEclairWallet): Unit = {
    val sendView: ChainSendView =
      new ChainSendView(fromWallet, badge = None, visibilityRes = -1)

    def makeFunding(
        fundingAmount: Satoshi,
        feeratePerKw: FeeratePerKw,
        local: PublicKey,
        remote: PublicKey
    ): Future[GenerateTxResponse] =
      fromWallet.makeFundingTx(
        Script.write(Script pay2wsh Scripts.multiSig2of2(local, remote).toList),
        fundingAmount,
        feeratePerKw
      )

    def makeFakeFunding(
        amount: Satoshi,
        feeratePerKw: FeeratePerKw
    ): Future[GenerateTxResponse] =
      makeFunding(
        amount,
        feeratePerKw,
        randomKey.publicKey,
        randomKey.publicKey
      )

    def makeRealFunding(
        data: DATA_WAIT_FOR_FUNDING_INTERNAL,
        amount: Satoshi
    ): Future[GenerateTxResponse] =
      makeFunding(
        amount,
        data.initFunder.fundingFeeratePerKw,
        data.lastSent.fundingPubkey,
        data.remoteParams.fundingPubKey
      )

    def doFundRunnable(
        channel: ChannelNormal,
        response: GenerateTxResponse
    ): Runnable = UITask {
      // At this point we have a real signed funding, relay it to channel and indicate progress
      sendView.switchToSpinner(alert)
      channel process response
    }

    def attempt(alert: AlertDialog): Unit = {
      def revertInformDismiss(reason: Throwable): Unit =
        runAnd(alert.dismiss)(revertAndInform(reason))
      runFutureProcessOnUI(
        makeFakeFunding(
          sendView.manager.resultMsat.truncateToSatoshi,
          feeView.rate
        ),
        revertInformDismiss
      ) { fakeResponse =>
        // It is fine to use the first map element here: we send to single address so response will always have a single element (change not included)
        // For signing wallet fake response sends to random p2wsh, for watching wallet fake response is also signed with random private key (needs another update)
        val totalFundAmount =
          fakeResponse.pubKeyScriptToAmount.values.head.toMilliSatoshi
        val finalSendButton =
          sendView.chainConfirmView.chainButtonsView.chainNextButton

        sendView.switchToSpinner(alert)
        stopAcceptingIncomingOffers()

        def processLocalFunding(): Unit =
          new NCFunderOpenHandler(
            hasInfo.remoteInfo,
            fundingAmount = totalFundAmount.truncateToSatoshi,
            feeView.rate,
            LNParams.cm
          ) {
            override def onChanPersisted(
                data: DATA_WAIT_FOR_FUNDING_CONFIRMED,
                chan: ChannelNormal
            ): Unit = implantAndBroadcast(data, fromWallet, chan)
            override def onFailure(reason: Throwable): Unit =
              revertInformDismiss(reason)

            override def onAwaitFunding(
                data: DATA_WAIT_FOR_FUNDING_INTERNAL
            ): Unit =
              makeRealFunding(
                data,
                amount = totalFundAmount.truncateToSatoshi
              ) onComplete {
                case Failure(reason) =>
                  makeChanListener.onException((reason, freshChannel, data))
                case Success(signedTx) =>
                  doFundRunnable(freshChannel, signedTx).run
              }
          }

        def processHardwareFunding(masterFingerprint: Long): Unit =
          new NCFunderOpenHandler(
            hasInfo.remoteInfo,
            fundingAmount = totalFundAmount.truncateToSatoshi,
            feeView.rate,
            LNParams.cm
          ) {
            override def onChanPersisted(
                data: DATA_WAIT_FOR_FUNDING_CONFIRMED,
                chan: ChannelNormal
            ): Unit = implantAndBroadcast(data, fromWallet, chan)
            override def onFailure(reason: Throwable): Unit =
              revertInformDismiss(reason)

            override def onAwaitFunding(
                data: DATA_WAIT_FOR_FUNDING_INTERNAL
            ): Unit =
              runFutureProcessOnUI(
                makeRealFunding(data, totalFundAmount.truncateToSatoshi),
                revertInformDismiss
              ) { fakeSigResponse =>
                // At this point real p2wsh is known, but tx is still signed with random private key, we need HW to properly sign it
                val psbt = prepareBip84Psbt(fakeSigResponse, masterFingerprint)
                sendView.switchToHardwareOutgoing(alert, psbt)

                sendView.chainReaderView.onSignedTx = signedTx =>
                  UITask {
                    val realSigReponse =
                      fakeSigResponse.withReplacedTx(signedTx)
                    if (
                      realSigReponse.tx.txOut.toSet != fakeSigResponse.tx.txOut.toSet
                    ) disconnectListenersAndFinish()
                    finalSendButton setOnClickListener onButtonTap(
                      doFundRunnable(freshChannel, realSigReponse).run
                    )
                    sendView.switchToConfirm(
                      alert,
                      totalFundAmount,
                      realSigReponse.fee.toMilliSatoshi
                    )
                  }.run
              }
          }

        if (fromWallet.isSigning) {
          finalSendButton setOnClickListener onButtonTap(processLocalFunding())
          sendView.switchToConfirm(
            alert,
            totalFundAmount,
            fakeResponse.fee.toMilliSatoshi
          )
        } else if (fromWallet.hasFingerprint)
          processHardwareFunding(fromWallet.info.core.masterFingerprint.get)
        else disconnectListenersAndFinish()
      }
    }

    lazy val alert = {
      val fundTitle = new TitleView(getString(R.string.rpa_open_nc))
      val builder = titleBodyAsViewBuilder(
        fundTitle.asColoredView(chainWalletBackground(fromWallet)),
        sendView.manager.content
      )
      addFlowChip(
        fundTitle.flow,
        getString(R.string.dialog_send_btc_from).format(fromWallet.info.label),
        R.drawable.border_yellow
      )
      def setMax(fundAlert: AlertDialog): Unit =
        sendView.manager.updateText(fromWallet.info.lastBalance.toMilliSatoshi)

      mkCheckFormNeutral(
        attempt,
        none,
        setMax,
        builder,
        R.string.dialog_ok,
        R.string.dialog_cancel,
        R.string.dialog_max
      )
    }

    lazy val feeView =
      new FeeView(FeeratePerByte(1L.sat), sendView.body) {
        rate =
          LNParams.feeRates.info.onChainFeeConf.feeEstimator.getFeeratePerKw(
            LNParams.feeRates.info.onChainFeeConf.feeTargets.fundingBlockTarget
          )

        val onChange = firstLast[Unit] { _ =>
          makeFakeFunding(
            sendView.manager.resultMsat.truncateToSatoshi,
            rate
          ).onComplete {
            case Success(res) =>
              update(
                feeOpt = Some(res.fee.toMilliSatoshi),
                showIssue = false
              )
            case Failure(exc) =>
              update(
                feeOpt = None,
                showIssue =
                  sendView.manager.resultMsat >= LNParams.minChanDustLimit
              )
          }
        }

        override def update(
            feeOpt: Option[MilliSatoshi],
            showIssue: Boolean
        ): Unit = UITask {
          updatePopupButton(getPositiveButton(alert), feeOpt.isDefined)
          super.update(feeOpt, showIssue)
        }.run
      }

    // Automatically update a candidate transaction each time user changes amount value
    sendView.manager.inputAmount.addTextChangedListener(
      onTextChange(_ => feeView.onChange(()))
    )
    feeView.update(feeOpt = None, showIssue = false)
  }

  def fundNewChannel(view: View): Unit = {
    if (LNParams.chainWallets.usableWallets.size == 1) {
      // We have a single built-in wallet, no need to choose
      doFundNewChannel(LNParams.chainWallets.lnWallet)
    } else
      bringChainWalletChooser(getString(R.string.rpa_open_nc)) { wallet =>
        // We have wallet candidates to spend from here
        doFundNewChannel(wallet)
      }
  }

  def sharePeerSpecificNodeId(view: View): Unit = share(
    hasInfo.remoteInfo.nodeSpecificPubKey.toString
  )

  def requestHostedChannel(view: View): Unit = askHostedChannel(randomBytes32)

  def askHostedChannel(secret: ByteVector32): Unit = {
    // show warning exit-scam warning
    val builder = new AlertDialog.Builder(this, R.style.DialogTheme)
      .setTitle(R.string.rpa_request_hc)
      .setMessage(getString(R.string.rpa_hc_warn).html)

    mkCheckForm(
      confirmedAcceptAskHostedChannel,
      none,
      builder,
      R.string.dialog_ok,
      R.string.dialog_cancel
    )

    def confirmedAcceptAskHostedChannel(alert: AlertDialog): Unit = {
      alert.dismiss
      doAskHostedChannel(secret)
    }
  }

  def doAskHostedChannel(secret: ByteVector32): Unit = {
    // Switch view first since HC may throw immediately
    setVis(isVisible = false, viewYesFeatureSupport)
    stopAcceptingIncomingOffers()

    // We only need local params to extract defaultFinalScriptPubKey
    val params =
      LNParams.makeChannelParams(isFunder = false, LNParams.minChanDustLimit)
    new HCOpenHandler(
      hasInfo.remoteInfo,
      secret,
      params.defaultFinalScriptPubKey,
      LNParams.cm
    ) {
      def onEstablished(cs: Commitments, channel: ChannelHosted): Unit =
        implant(cs, channel)

      def onFailure(reason: Throwable): Unit = UITask {
        // We need to disconnect instead of just showing an error because of HC specifics
        // remote peer awaits for our response and won't react to another HC open request
        WalletApp.app.quickToast(reason.getMessage)
        disconnectListenersAndFinish()
        whenBackPressed.run
      }.run
    }
  }

  def revertAndInform(reason: Throwable): Unit = UITask {
    setVis(isVisible = criticalSupportAvailable, viewYesFeatureSupport)
    CommsTower.listenNative(Set(incomingAcceptingListener), hasInfo.remoteInfo)
    val details = Option(reason.getMessage).getOrElse(reason.stackTraceAsString)
    WalletApp.app.quickToast(details)
  }.run

  def stopAcceptingIncomingOffers(): Unit = {
    CommsTower.listenNative(Set(incomingIgnoringListener), hasInfo.remoteInfo)
    CommsTower.rmListenerNative(hasInfo.remoteInfo, incomingAcceptingListener)
  }

  def implantAndBroadcast(
      data: DATA_WAIT_FOR_FUNDING_CONFIRMED,
      fromWallet: ElectrumEclairWallet,
      freshChannel: Channel
  ): Unit = {
    // At this point channel has been persisted, we can now safely broadcast a funding tx and add channel to runtime map
    data.fundingTx.foreach(fromWallet.broadcast)
    implant(data.commitments, freshChannel)
  }

  def implant(cs: Commitments, freshChannel: Channel): Unit = {
    RemotePeerActivity.implantNewChannel(cs, freshChannel)
    disconnectListenersAndFinish()
  }

  def disconnectListenersAndFinish(): Unit = {
    CommsTower.rmListenerNative(hasInfo.remoteInfo, incomingAcceptingListener)
    CommsTower.rmListenerNative(hasInfo.remoteInfo, incomingIgnoringListener)
    CommsTower.rmListenerNative(hasInfo.remoteInfo, viewUpdatingListener)
    finish
  }
}
