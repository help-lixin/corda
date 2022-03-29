package net.corda.core.conclave.common

import net.corda.core.serialization.CordaSerializable

@CordaSerializable
enum class EnclaveCommand {
    InitPostOfficeToRemoteEnclave,
    VerifyTransactionWithSignatures,
    VerifyTransactionWithoutSignatures,
    EncryptTransactionForLocal,
    EncryptLedgerTransactionForRemote,
    EncryptVerifiedTransactionForRemote,
    DeserializeTransactionReturnHash;
    fun serialize(): String {
        return this.name
    }
}

fun String.toEnclaveCommand(): EnclaveCommand {
    return EnclaveCommand.valueOf(this)
}