"""Supported voice intents and their safe response metadata."""

from typing import Any, Dict


INTENTS: Dict[str, Dict[str, Any]] = {
    "OPEN_CAMERA": {
        "examples": [
            "open camera", "start camera", "launch camera", "turn on my camera",
            "I want to use the camera", "show camera view", "open the live camera",
            "bring up the camera", "please open the camera", "could you open the camera",
        ],
        "message": "Opening the live camera view.",
        "parameters": {"screen": "live_assist"},
    },
    "START_DETECTION": {
        "examples": [
            "start object detection", "detect objects", "what is around me",
            "describe my surroundings", "tell me what is in front of me",
            "identify nearby objects", "what can you see around me",
            "start live assist", "show me what is ahead",
            "Can you show me what's around", "tell me what is ahead",
            "what can you see in front of me", "what is next to me",
        ],
        "message": "Starting live object detection.",
        "requires_confirmation": True,
        "parameters": {"screen": "live_assist", "mode": "continuous"},
    },
    "OPEN_NAVIGATION": {
        "examples": [
            "open navigation", "start navigation", "I want to go somewhere",
            "help me find a location", "take me somewhere", "show walking directions",
            "open pedestrian navigation", "help me get to a place",
            "take me to a location",
        ],
        "message": "Opening pedestrian navigation.",
        "parameters": {"screen": "navigate"},
    },
    "OPEN_OCR": {
        "examples": [
            "read this document", "read the text", "scan this page",
            "read what is written here", "start text recognition", "read the sign",
            "open the text reader", "read this label", "I can't read this, help me",
            "please scan this sign",
        ],
        "message": "Opening the text reader.",
        "parameters": {"screen": "read_text"},
    },
    "GO_HOME": {
        "examples": [
            "go home", "open home screen", "take me home", "return to dashboard",
            "show the home screen", "go to the home screen",
            "take me back to the beginning",
        ],
        "message": "Returning to the home screen.",
        "parameters": {"screen": "home"},
    },
    "GO_BACK": {
        "examples": [
            "go back", "previous screen", "return to previous page",
            "take me back", "go to the previous screen", "back one screen",
        ],
        "message": "Going back.",
    },
    "STOP_SPEAKING": {
        "examples": [
            "stop speaking", "stop talking", "be quiet", "silence",
            "shut up", "please stop speaking", "mute navis",
        ],
        "message": "",
    },
    "REPEAT_RESPONSE": {
        "examples": [
            "repeat that", "say that again", "repeat your answer",
            "what did you say", "repeat the previous response", "say it again",
        ],
        "message": "Repeating the previous response.",
    },
    "OPEN_SETTINGS": {
        "examples": [
            "open settings", "change settings", "I want to change preferences",
            "show settings", "open preferences", "change my preferences",
        ],
        "message": "Opening settings.",
        "parameters": {"screen": "settings"},
    },
    "CHECK_STATUS": {
        "examples": [
            "check system status", "system status", "check device status",
            "is navis working", "are you working", "check connectivity",
            "give me status", "give me the status", "what is the system status",
        ],
        "message": "Checking NAVIS system status.",
    },
    "STOP_NAVIGATION": {
        "examples": [
            "stop navigation", "end navigation", "stop route guidance",
            "cancel navigation", "finish navigation",
        ],
        "message": "Stopping navigation.",
    },
    "CANCEL_OPERATION": {
        "examples": [
            "cancel current operation", "cancel the current task", "cancel that",
            "cancel this operation", "cancel the current action",
        ],
        "message": "Cancelled the current operation.",
    },
}
