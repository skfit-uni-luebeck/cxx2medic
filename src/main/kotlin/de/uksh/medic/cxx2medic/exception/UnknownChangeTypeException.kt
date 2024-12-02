package de.uksh.medic.cxx2medic.exception

private val defaultChangeTypes = setOf("created", "updated", "deleted")

class UnknownChangeTypeException(changeType: String, expected: Set<String> = defaultChangeTypes):
    Exception("Unknown change type [actual=$changeType, expected=<${expected.joinToString()}>]")