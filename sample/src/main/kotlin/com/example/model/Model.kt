package com.example.model

import com.example.geometry.Sized

class Rectangular(val width: Int, val height: Int, val color: String)

class Rope(val length: Int)

class Clicks(var count: Int)

open class Frame(val width: Int, val height: Int)

class PictureFrame(val material: String) : Frame(10, 20)

class Window : Frame(30, 40), Sized
