package com.jadxmp.input

/** Malformed optional Signature metadata, distinct from mandatory declaration or executable input errors. */
public class InvalidGenericSignatureEncoding(message: String) : IllegalArgumentException(message)
