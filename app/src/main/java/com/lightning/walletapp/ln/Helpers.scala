package com.lightning.walletapp.ln

import fr.acinq.bitcoin._
import com.lightning.walletapp.ln.wire._
import com.lightning.walletapp.ln.Scripts._
import com.lightning.walletapp.ln.crypto.ShaChain._
import com.lightning.walletapp.ln.crypto.Generators._
import fr.acinq.bitcoin.Crypto.{Point, PublicKey, Scalar}
import scala.util.{Success, Try}


object Helpers { me =>
  def makeLocalTxs(commitTxNumber: Long, localParams: LocalParams,
                   remoteParams: AcceptChannel, commitmentInput: InputInfo,
                   localPerCommitmentPoint: Point, spec: CommitmentSpec) = {

    val localHtlcPubkey = derivePubKey(localParams.htlcBasepoint, localPerCommitmentPoint)
    val localDelayedPaymentPubkey = derivePubKey(localParams.delayedPaymentBasepoint, localPerCommitmentPoint)
    val localRevocationPubkey = revocationPubKey(remoteParams.revocationBasepoint, localPerCommitmentPoint)
    val remotePaymentPubkey = derivePubKey(remoteParams.paymentBasepoint, localPerCommitmentPoint)
    val remoteHtlcPubkey = derivePubKey(remoteParams.htlcBasepoint, localPerCommitmentPoint)

    val commitTx =
      Scripts.makeCommitTx(commitmentInput, commitTxNumber, localParams.paymentBasepoint, remoteParams.paymentBasepoint,
        localParams.isFunder, localParams.dustLimit, localRevocationPubkey, remoteParams.toSelfDelay, localDelayedPaymentPubkey,
        remotePaymentPubkey, localHtlcPubkey, remoteHtlcPubkey, spec)

    val htlcTimeoutTxs \ htlcSuccessTxs =
      Scripts.makeHtlcTxs(commitTx.tx, localParams.dustLimit, localRevocationPubkey,
        remoteParams.toSelfDelay, localDelayedPaymentPubkey, localHtlcPubkey, remoteHtlcPubkey, spec)

    (commitTx, htlcTimeoutTxs, htlcSuccessTxs)
  }

  def makeRemoteTxs(commitTxNumber: Long, localParams: LocalParams,
                    remoteParams: AcceptChannel, commitmentInput: InputInfo,
                    remotePerCommitmentPoint: Point, spec: CommitmentSpec) = {

    val localHtlcPubkey = derivePubKey(localParams.htlcBasepoint, remotePerCommitmentPoint)
    val localPaymentPubkey = derivePubKey(localParams.paymentBasepoint, remotePerCommitmentPoint)
    val remoteRevocationPubkey = revocationPubKey(localParams.revocationBasepoint, remotePerCommitmentPoint)
    val remoteDelayedPaymentPubkey = derivePubKey(remoteParams.delayedPaymentBasepoint, remotePerCommitmentPoint)
    val remoteHtlcPubkey = derivePubKey(remoteParams.htlcBasepoint, remotePerCommitmentPoint)

    val commitTx = Scripts.makeCommitTx(commitmentInput, commitTxNumber, remoteParams.paymentBasepoint,
      localParams.paymentBasepoint, !localParams.isFunder, remoteParams.dustLimitSat, remoteRevocationPubkey,
      localParams.toSelfDelay, remoteDelayedPaymentPubkey, localPaymentPubkey, remoteHtlcPubkey,
      localHtlcPubkey, spec)

    val htlcTimeoutTxs \ htlcSuccessTxs = Scripts.makeHtlcTxs(commitTx.tx, remoteParams.dustLimitSat,
      remoteRevocationPubkey, localParams.toSelfDelay, remoteDelayedPaymentPubkey, remoteHtlcPubkey,
      localHtlcPubkey, spec)

    (commitTx, htlcTimeoutTxs, htlcSuccessTxs,
      remoteHtlcPubkey, remoteRevocationPubkey)
  }

  object Closing {
    type SuccessAndClaim = (HtlcSuccessTx, ClaimDelayedOutputTx)
    type TimeoutAndClaim = (HtlcTimeoutTx, ClaimDelayedOutputTx)

    def isValidFinalScriptPubkey(raw: BinaryData) = Try(Script parse raw) match {
      case Success(OP_DUP :: OP_HASH160 :: OP_PUSHDATA(pkh, _) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) => pkh.data.size == 20
      case Success(OP_HASH160 :: OP_PUSHDATA(scriptHash, _) :: OP_EQUAL :: Nil) => scriptHash.data.size == 20
      case Success(OP_0 :: OP_PUSHDATA(pubkeyHash, _) :: Nil) if pubkeyHash.length == 20 => true
      case Success(OP_0 :: OP_PUSHDATA(scriptHash, _) :: Nil) if scriptHash.length == 32 => true
      case _ => false
    }

    def makeFirstClosing(commitments: Commitments, localScriptPubkey: BinaryData, remoteScriptPubkey: BinaryData) = {
      val estimatedWeight: Int = Transaction.weight(Scripts.addSigs(makeFunderClosingTx(commitments.commitInput, localScriptPubkey,
        remoteScriptPubkey, Satoshi(0), Satoshi(0), commitments.localCommit.spec), commitments.localParams.fundingPrivKey.publicKey,
        commitments.remoteParams.fundingPubkey, "aa" * 71, "bb" * 71).tx)

      val closingFee = Scripts.weight2fee(commitments.localCommit.spec.feeratePerKw, estimatedWeight)
      makeClosing(commitments, closingFee, localScriptPubkey, remoteScriptPubkey)
    }

    def makeClosing(commitments: Commitments, closingFee: Satoshi, local: BinaryData, remote: BinaryData) = {
      val theirDustIsHigherThanOurs = commitments.localParams.dustLimit < commitments.remoteParams.dustLimitSat
      val dustLimit = if (theirDustIsHigherThanOurs) commitments.remoteParams.dustLimitSat else commitments.localParams.dustLimit
      val closing = makeFunderClosingTx(commitments.commitInput, local, remote, dustLimit, closingFee, commitments.localCommit.spec)

      val localClosingSig = Scripts.sign(closing, commitments.localParams.fundingPrivKey)
      val closingSigned = ClosingSigned(commitments.channelId, closingFee.amount, localClosingSig)
      require(isValidFinalScriptPubkey(remote), "Invalid remoteScriptPubkey")
      require(isValidFinalScriptPubkey(local), "Invalid localScriptPubkey")
      ClosingTxProposed(closing, closingSigned)
    }

    def makeFunderClosingTx(commitTxInput: InputInfo, localScriptPubKey: BinaryData, remoteScriptPubKey: BinaryData,
                            dustLimit: Satoshi, closingFee: Satoshi, spec: CommitmentSpec): ClosingTx = {

      require(spec.htlcs.isEmpty, "No HTLCs allowed")
      val toRemoteAmount: Satoshi = MilliSatoshi(spec.toRemoteMsat)
      val toLocalAmount: Satoshi = MilliSatoshi(spec.toLocalMsat) - closingFee
      val toLocalOutput = if (toLocalAmount < dustLimit) Nil else TxOut(toLocalAmount, localScriptPubKey) :: Nil
      val toRemoteOutput = if (toRemoteAmount < dustLimit) Nil else TxOut(toRemoteAmount, remoteScriptPubKey) :: Nil
      val input = TxIn(commitTxInput.outPoint, Array.emptyByteArray, sequence = 0xffffffffL) :: Nil
      val tx = Transaction(version = 2, input, toLocalOutput ++ toRemoteOutput, lockTime = 0)
      ClosingTx(commitTxInput, LexicographicalOrdering sort tx)
    }

    def claimCurrentLocalCommitTxOutputs(commitments: Commitments, bag: PaymentInfoBag) = {
      val localPerCommitmentPoint = perCommitPoint(commitments.localParams.shaSeed, commitments.localCommit.index.toInt)
      val localRevocationPubkey = revocationPubKey(commitments.remoteParams.revocationBasepoint, localPerCommitmentPoint)
      val localDelayedPrivkey = derivePrivKey(commitments.localParams.delayedPaymentKey, localPerCommitmentPoint)

      def makeClaimDelayedOutput(tx: Transaction) = for {
        claimDelayed <- Scripts.makeClaimDelayedOutputTx(tx, localRevocationPubkey, commitments.remoteParams.toSelfDelay,
          localDelayedPrivkey.publicKey, commitments.localParams.defaultFinalScriptPubKey, LNParams.broadcaster.ratePerKwSat)

        sig = Scripts.sign(claimDelayed, localDelayedPrivkey)
        signed <- Scripts checkSpendable Scripts.addSigs(claimDelayed, sig)
      } yield signed

      val allSuccessTxs = for {
        HtlcTxAndSigs(info: HtlcSuccessTx, local, remote) <- commitments.localCommit.htlcTxsAndSigs
        paymentInfo <- bag.getPaymentInfo(hash = info.add.paymentHash).toOption
        success = Scripts.addSigs(info, local, remote, paymentInfo.preimage)
        delayed <- makeClaimDelayedOutput(success.tx).toOption
      } yield success -> delayed

      val allTimeoutTxs = for {
        HtlcTxAndSigs(info: HtlcTimeoutTx, local, remote) <- commitments.localCommit.htlcTxsAndSigs
        timeout = Scripts.addSigs(htlcTimeoutTx = info, localSig = local, remoteSig = remote)
        delayed <- makeClaimDelayedOutput(timeout.tx).toOption
      } yield timeout -> delayed

      // When local commit is spent our main output is also delayed
      val claimMainDelayedTx = makeClaimDelayedOutput(commitments.localCommit.commitTx.tx).toOption.toSeq
      LocalCommitPublished(claimMainDelayedTx, allSuccessTxs, allTimeoutTxs, commitments.localCommit.commitTx.tx)
    }

    // remoteCommit may refer to their current or next RemoteCommit, hence it is a separate parameter
    def claimRemoteCommitTxOutputs(commitments: Commitments, remoteCommit: RemoteCommit, bag: PaymentInfoBag) = {
      val localHtlcPrivkey = derivePrivKey(commitments.localParams.htlcKey, remoteCommit.remotePerCommitmentPoint)
      val feeRate = LNParams.broadcaster.ratePerKwSat

      val (remoteCommitTx, timeoutTxs, successTxs, remoteHtlcPubkey, remoteRevocationPubkey) =
        makeRemoteTxs(remoteCommit.index, commitments.localParams, commitments.remoteParams,
          commitments.commitInput, remoteCommit.remotePerCommitmentPoint, remoteCommit.spec)

      val finder = new PubKeyScriptIndexFinder(remoteCommitTx.tx)

      val claimSuccessTxs = for {
        HtlcTimeoutTx(_, _, add) <- timeoutTxs
        paymentInfo <- bag.getPaymentInfo(hash = add.paymentHash).toOption
        claimHtlcSuccessTx <- Scripts.makeClaimHtlcSuccessTx(finder, localHtlcPrivkey.publicKey, remoteHtlcPubkey,
          remoteRevocationPubkey, commitments.localParams.defaultFinalScriptPubKey, add, feeRate).toOption

        signature = Scripts.sign(claimHtlcSuccessTx, key = localHtlcPrivkey)
        signed = Scripts.addSigs(claimHtlcSuccessTx, signature, paymentInfo.preimage)
        success <- Scripts.checkSpendable(signed).toOption
      } yield success

      val claimTimeoutTxs = for {
        HtlcSuccessTx(_, _, add) <- successTxs
        claimHtlcTimeoutTx <- Scripts.makeClaimHtlcTimeoutTx(finder, localHtlcPrivkey.publicKey, remoteHtlcPubkey,
          remoteRevocationPubkey, commitments.localParams.defaultFinalScriptPubKey, add, feeRate).toOption

        sig = Scripts.sign(claimHtlcTimeoutTx, localHtlcPrivkey)
        signed = Scripts.addSigs(claimHtlcTimeoutTx, localSig = sig)
        timeout <- Scripts.checkSpendable(signed).toOption
      } yield timeout

      val main = claimRemoteMainOutput(commitments, remoteCommit.remotePerCommitmentPoint, remoteCommitTx.tx)
      main.copy(claimHtlcSuccess = claimSuccessTxs, claimHtlcTimeout = claimTimeoutTxs)
    }

    def claimRemoteMainOutput(commitments: Commitments, remotePerCommitmentPoint: Point, commitTx: Transaction) = {
      // May be a special case where we have lost our data and explicitly ask them to spend their local current commit
      val localPaymentPrivkey = derivePrivKey(commitments.localParams.paymentKey, remotePerCommitmentPoint)
      val finalScriptPubKey = commitments.localParams.defaultFinalScriptPubKey
      val feeRate = LNParams.broadcaster.ratePerKwSat

      val claimMain = for {
        claimP2WPKH <- Scripts.makeClaimP2WPKHOutputTx(commitTx, localPaymentPrivkey.publicKey, finalScriptPubKey, feeRate)
        signed = Scripts.addSigs(claimP2WPKH, Scripts.sign(claimP2WPKH, localPaymentPrivkey), localPaymentPrivkey.publicKey)
        main <- Scripts.checkSpendable(signed)
      } yield main

      RemoteCommitPublished(claimMain.toOption.toSeq,
        claimHtlcSuccess = Nil, claimHtlcTimeout = Nil, commitTx)
    }

    def claimRevokedRemoteCommitTxOutputs(commitments: Commitments, tx: Transaction, bag: PaymentInfoBag) = {
      val txNumber = Scripts.obscuredCommitTxNumber(number = Scripts.decodeTxNumber(tx.txIn.head.sequence, tx.lockTime),
        !commitments.localParams.isFunder, commitments.remoteParams.paymentBasepoint, commitments.localParams.paymentBasepoint)

      val index = moves(largestTxIndex - txNumber)
      getHash(commitments.remotePerCommitmentSecrets.hashes)(index) map { perCommitSecret =>
        // At the very least we should take both balances + HTLCs if could be found in database

        val remotePerCommitmentSecretScalar = Scalar(perCommitSecret)
        val remotePerCommitmentPoint = remotePerCommitmentSecretScalar.toPoint

        val finalScriptPubKey = commitments.localParams.defaultFinalScriptPubKey
        val localPrivkey = derivePrivKey(commitments.localParams.paymentKey, remotePerCommitmentPoint)
        val remoteDelayedPaymentKey = derivePubKey(commitments.remoteParams.delayedPaymentBasepoint, remotePerCommitmentPoint)
        val remoteRevocationPrivkey = revocationPrivKey(commitments.localParams.revocationSecret, remotePerCommitmentSecretScalar)
        val remoteRevocationPubkey = remoteRevocationPrivkey.publicKey
        val feeRate = LNParams.broadcaster.ratePerKwSat

        val claimMainTx = for {
          makeClaimP2WPKH <- Scripts.makeClaimP2WPKHOutputTx(tx, localPrivkey.publicKey, finalScriptPubKey, feeRate)
          signed = Scripts.addSigs(makeClaimP2WPKH, Scripts.sign(makeClaimP2WPKH, localPrivkey), localPrivkey.publicKey)
          main <- Scripts.checkSpendable(signed)
        } yield main

        val claimPenaltyTx = for {
          theirMainPenalty <- Scripts.makeMainPenaltyTx(tx, remoteRevocationPubkey,
            finalScriptPubKey, commitments.localParams.toSelfDelay, remoteDelayedPaymentKey, feeRate)

          sig = Scripts.sign(theirMainPenalty, remoteRevocationPrivkey)
          signed = Scripts.addSigs(theirMainPenalty, revocationSig = sig)
          their <- Scripts.checkSpendable(signed)
        } yield their

        val localHtlc = derivePubKey(commitments.localParams.htlcBasepoint, remotePerCommitmentPoint)
        val remoteHtlc = derivePubKey(commitments.remoteParams.htlcBasepoint, remotePerCommitmentPoint)
        val remoteRev = revocationPubKey(commitments.localParams.revocationBasepoint, remotePerCommitmentPoint)

        val matching = bag getAllRevoked txNumber
        val finder = new PubKeyScriptIndexFinder(tx)
        val offered = for (h160 \ _ <- matching) yield Scripts.htlcOffered(remoteHtlc, localHtlc, remoteRev, h160)
        val received = for (h160 \ expiry <- matching) yield Scripts.htlcReceived(remoteHtlc, localHtlc, remoteRev, h160, expiry)
        val redeemScripts = for (redeem <- offered ++ received) yield Script.write(Script pay2wsh redeem) -> Script.write(redeem)
        val redeemMap = redeemScripts.toMap

        val htlcPenaltyTxs = for {
          TxOut(_, publicKeyScript) <- tx.txOut
          redeemScript <- redeemMap get publicKeyScript
          htlcPenaltyTx <- Scripts.makeHtlcPenaltyTx(finder, redeemScript, finalScriptPubKey, feeRate * 4).toOption
          signed = Scripts.addSigs(htlcPenaltyTx, Scripts.sign(htlcPenaltyTx, remoteRevocationPrivkey), remoteRevocationPubkey)
          htlcPenalty <- Scripts.checkSpendable(signed).toOption
        } yield htlcPenalty

        RevokedCommitPublished(claimMainTx.toOption.toSeq,
          claimPenaltyTx.toOption.toSeq, htlcPenaltyTxs, tx)
      }
    }
  }

  object Funding {
    def makeFundingInputInfo(fundingTxHash: BinaryData, fundingTxOutputIndex: Int,
                             fundingSatoshis: Satoshi, fundingPubkey1: PublicKey,
                             fundingPubkey2: PublicKey): InputInfo = {

      val multisig = Scripts.multiSig2of2(fundingPubkey1, fundingPubkey2)
      val fundingTxOut = TxOut(fundingSatoshis, Script pay2wsh multisig)
      val outPoint = OutPoint(fundingTxHash, fundingTxOutputIndex)
      InputInfo(outPoint, fundingTxOut, Script write multisig)
    }

    // Assuming we are always a funder
    def makeFirstFunderCommitTxs(cmd: CMDOpenChannel, remoteParams: AcceptChannel,
                                 fundingTxHash: BinaryData, fundingTxOutputIndex: Int,
                                 remoteFirstPoint: Point) = {

      val toLocalMsat = cmd.realFundingAmountSat * 1000L - cmd.pushMsat
      val commitmentInput = makeFundingInputInfo(fundingTxHash, fundingTxOutputIndex,
        Satoshi(cmd.realFundingAmountSat), cmd.localParams.fundingPrivKey.publicKey,
        remoteParams.fundingPubkey)

      val localPerCommitmentPoint = perCommitPoint(cmd.localParams.shaSeed, 0L)
      val localSpec = CommitmentSpec(cmd.initialFeeratePerKw, toLocalMsat, cmd.pushMsat)
      val remoteSpec = CommitmentSpec(cmd.initialFeeratePerKw, cmd.pushMsat, toLocalMsat)
      val (localCommitTx, _, _) = makeLocalTxs(0L, cmd.localParams, remoteParams, commitmentInput, localPerCommitmentPoint, localSpec)
      val (remoteCommitTx, _, _, _, _) = makeRemoteTxs(0L, cmd.localParams, remoteParams, commitmentInput, remoteFirstPoint, remoteSpec)
      (localSpec, localCommitTx, remoteSpec, remoteCommitTx)
    }
  }
}