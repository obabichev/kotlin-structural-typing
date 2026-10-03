package com.example.dependency

// These classes know nothing about com.example.geometry: this module is compiled without the structural plugin, and
// nothing here names Sized, Measurable or Counter. structural-runtime adds those interfaces as the classes are loaded.

class Rectangular(val width: Int, val height: Int, val color: String)

class Rope(val length: Int)

class Clicks(var count: Int)

class Unrelated(val depth: Int)
