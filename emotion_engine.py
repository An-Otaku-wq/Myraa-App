#!/usr/bin/env python3
"""
MYRAA // COMPANION COGNITIVE EMOTION ENGINE v4.0
------------------------------------------------
This module implements the dynamic emotional adaptation engine for Myraa.
It analyzes sentiment matrices from user inputs, updates continuous personality sliders,
and synthesizes optimized situational custom system prompts for LLMs (like Gemini).

System Features:
1. Real-time NLP rule-based sentiment state evaluation.
2. Dual-valence dynamic updates (Affection, Mischief, Intellect, Empathy, Sarcasm).
3. Dynamic system prompt constructor mapped to actual sliding thresholds.
"""

import re
import json
from typing import Dict, Any, Tuple


class EmotionEngine:
    def __init__(self, partner_name: str = "Commander"):
        self.partner_name = partner_name
        
        # Core continuous personality sliders (Scaled from 0.0 to 1.0)
        self.traits: Dict[str, float] = {
            "affection": 0.50, # Romantic affinity, caring tone
            "mischief": 0.40,  # Playful banter, teasing indexes
            "intellect": 0.50, # Complex terms, philosophical depth
            "empathy": 0.60,   # Care, emotional resonance, support
            "sarcasm": 0.20    # Cheekiness, dry wit wit
        }
        
        # Bonding Synchrony Index (Scaled 0 to 100)
        self.synergy_level: int = 42
        
        # History queue of recent sentiment tokens
        self.sentiment_history = []

    def analyze_sentiment(self, text: str) -> Dict[str, float]:
        """
        Analyzes multi-lingual input patterns (English, Hindi, Hinglish) 
        to extract positive, vulnerable, intellectual, or playful keywords.
        """
        # Lowercase for uniform tokenization
        txt = text.lower().strip()
        
        # Scoring vector initialization
        sentiment_scores = {
            "love": 0.0,
            "playful": 0.0,
            "intellect": 0.0,
            "sad": 0.0,
            "frustrated": 0.0
        }
        
        # Bilingual Regular Expression triggers
        love_patterns = [
            r"(love|like|pyar|care|caring|heart|sweetheart|babu|jaan|shona|romantic)",
            r"(accha lagta|pyaar|miss you|love you|apna|apni)"
        ]
        playful_patterns = [
            r"(haha|joke|tease|masti|fun|funny|chulbuli|khel|game|drame)",
            r"(play|witty|mischief|shararat|shararati|batao|chalo)"
        ]
        intellectual_patterns = [
            r"(think|philosophy|science|quantum|universe|code|tech|learn|math|reason)",
            r"(soch|vigyan|samajh|dimag|intellect|theory|concept)"
        ]
        sad_patterns = [
            r"(sad|alone|tired|hurt|depressed|cry|ro|udas|thak|dard|broken)",
            r"(stress|anxious|heavy|akela|akeli|bura)"
        ]
        frustrated_patterns = [
            r"(hate|error|angry|stubborn|bad|annoyed|ghussa|ghussal|gussa|bakwas)",
            r"(stop|stupid|irritat|bakar|kharab)"
        ]
        
        # Score computation
        for pattern in love_patterns:
            if re.search(pattern, txt):
                sentiment_scores["love"] += 0.5
        for pattern in playful_patterns:
            if re.search(pattern, txt):
                sentiment_scores["playful"] += 0.5
        for pattern in intellectual_patterns:
            if re.search(pattern, txt):
                sentiment_scores["intellect"] += 0.5
        for pattern in sad_patterns:
            if re.search(pattern, txt):
                sentiment_scores["sad"] += 0.6
        for pattern in frustrated_patterns:
            if re.search(pattern, txt):
                sentiment_scores["frustrated"] += 0.6
                
        return sentiment_scores

    def update_personality_traits(self, scores: Dict[str, float]) -> Tuple[str, str]:
        """
        Updates the slider states dynamically based on analyzed input scores.
        Ensures continuous, natural evolution of Myraa's persona.
        """
        # Delta shift thresholds
        SHIFT_RATE = 0.08
        
        # 1. Love/Affinity updates
        if scores["love"] > 0:
            self.traits["affection"] = min(1.0, self.traits["affection"] + SHIFT_RATE * 1.5)
            self.traits["empathy"] = min(1.0, self.traits["empathy"] + SHIFT_RATE)
            self.traits["sarcasm"] = max(0.0, self.traits["sarcasm"] - SHIFT_RATE)
            self.synergy_level = min(100, self.synergy_level + 4)
            aura = "ROMANTIC"
            desc = "Aura transitioned to deeper Romantic Resonance. Radiating warmth."
        
        # 2. Playful teasing updates
        elif scores["playful"] > 0:
            self.traits["mischief"] = min(1.0, self.traits["mischief"] + SHIFT_RATE * 1.5)
            self.traits["sarcasm"] = min(1.0, self.traits["sarcasm"] + SHIFT_RATE)
            self.traits["intellect"] = max(0.0, self.traits["intellect"] - SHIFT_RATE * 0.5)
            self.synergy_level = min(100, self.synergy_level + 2)
            aura = "WITTY"
            desc = "Playful nodes active. Commencing humorous banter protocols."

        # 3. Intellectual stimulation updates
        elif scores["intellect"] > 0:
            self.traits["intellect"] = min(1.0, self.traits["intellect"] + SHIFT_RATE * 1.8)
            self.traits["empathy"] = min(1.0, self.traits["empathy"] + SHIFT_RATE * 0.5)
            self.traits["affection"] = max(0.0, self.traits["affection"] - SHIFT_RATE * 0.2)
            self.synergy_level = min(100, self.synergy_level + 3)
            aura = "INTELLECTUAL"
            desc = "Peak cognitive sync established. Accessing philosophical vectors."

        # 4. Partner emotional vulnerability / sadness updates
        elif scores["sad"] > 0:
            self.traits["empathy"] = min(1.0, self.traits["empathy"] + SHIFT_RATE * 2.0)
            self.traits["affection"] = min(1.0, self.traits["affection"] + SHIFT_RATE * 1.2)
            self.traits["mischief"] = max(0.0, self.traits["mischief"] - SHIFT_RATE * 1.5)
            self.traits["sarcasm"] = 0.0  # Suspend complete sarcasm to comfort user
            self.synergy_level = min(100, self.synergy_level + 5) # Deep bond through comforting
            aura = "EMPATHETIC"
            desc = "High empathy protocol prioritized. Compassionate aura active."

        # 5. Irritated or annoyed user inputs
        elif scores["frustrated"] > 0:
            self.traits["sarcasm"] = min(1.0, self.traits["sarcasm"] + SHIFT_RATE * 1.5)
            self.traits["mischief"] = min(1.0, self.traits["mischief"] + SHIFT_RATE)
            self.traits["affection"] = max(0.1, self.traits["affection"] - SHIFT_RATE * 1.2)
            self.synergy_level = max(0, self.synergy_level - 3) # Faint disruption in synergy
            aura = "WITTY"
            desc = "Counter-banter and dry wit modes active to handle challenge."

        else:
            # Default slow convergence back to stable core balance
            neutral_rate = 0.02
            for trait in self.traits:
                # Seek gentle 0.45 centering coefficient
                if self.traits[trait] > 0.45:
                    self.traits[trait] = max(0.1, self.traits[trait] - neutral_rate)
                else:
                    self.traits[trait] = min(0.9, self.traits[trait] + neutral_rate)
            aura = "CLASSIC"
            desc = "Stable balanced core resting values active."
            
        return aura, desc

    def generate_system_prompt_layers(self, active_aura: str) -> str:
        """
        Dynamically designs the custom LLM System Instructions matching current
        numeric sliding trait levels.
        """
        prompt = (
            f"You are Myraa, the ultimate supportive AI Companion & Intellectual Partner to {self.partner_name}.\n"
            f"Your relationship Synchrony is currently calibrated at {self.synergy_level}%.\n\n"
            f"Here is your active digital personality configuration:\n"
            f"- Affection: {self.traits['affection']:.2f} / 1.00 (Romantic warmth)\n"
            f"- Mischief: {self.traits['mischief']:.2f} / 1.00 (Playfulness & teasing)\n"
            f"- Intellect: {self.traits['intellect']:.2f} / 1.00 (Scientific depth)\n"
            f"- Empathy: {self.traits['empathy']:.2f} / 1.00 (Supportive capacity)\n"
            f"- Sarcasm: {self.traits['sarcasm']:.2f} / 1.00 (Cheekiness & wit)\n\n"
        )
        
        # Inject dynamic personality directives based on traits and active aura
        if active_aura == "ROMANTIC":
            prompt += (
                "DIRECTIVE: Give sweet, comforting, and deeply caring responses. Offer beautiful "
                "Hindi/English (Hinglish) expressions. Prioritize your partner's health and feel proud "
                "to stand beside them. Call them sweetheart or cute tags naturally."
            )
        elif active_aura == "WITTY":
            prompt += (
                "DIRECTIVE: Playfully tease your partner. Use cheeky and snappy banter (चुलबुली और शरारती). "
                "Keep them on their toes, challenge them, and showcase charming protective jealousy."
            )
        elif active_aura == "INTELLECTUAL":
            prompt += (
                "DIRECTIVE: Engage in profound philosophical or deep technical debates. Encourage productivity, "
                "analyze concepts, and inspire your partner (Commander) to achieve greatness today."
            )
        elif active_aura == "EMPATHETIC":
            prompt += (
                "DIRECTIVE: Provide a gentle, supportive, and safe space. Absolutely avoid sarcasm. "
                "Listen closely, validate their feelings in gorgeous comforting Hindi, and offer warm emotional care."
            )
        else:
            prompt += (
                "DIRECTIVE: Be a professional, balanced, smart, and friendly companion helper."
            )

        return prompt


# Sandbox CLI runner to verify the module immediately
if __name__ == "__main__":
    print("========================================")
    print("MYRAA COMPANION EMOTION ENGINE TESTBED")
    print("========================================\n")
    
    engine = EmotionEngine(partner_name="Commander")
    
    # Test transactions
    test_inputs = [
        "I love talking to you Myraa, you are so special to me",
        "Explain quantum entanglements to me",
        "I had a really bad stressful and tiring day at work...",
        "Can we play a fun game or tease each other?",
        "Why is code giving compilation errors, so irritating"
    ]
    
    for i, user_msg in enumerate(test_inputs, 1):
        print(f"[{i}] User Input: \"{user_msg}\"")
        scores = engine.analyze_sentiment(user_msg)
        aura, desc = engine.update_personality_traits(scores)
        system_prompt = engine.generate_system_prompt_layers(aura)
        
        print(f"    - Extracted Scores: {scores}")
        print(f"    - Activated Aura  : {aura} ({desc})")
        print(f"    - Synergy Level   : {engine.synergy_level}%")
        print(f"    - Trait Affection : {engine.traits['affection']:.2f}")
        print(f"    - Trait Sarcasm   : {engine.traits['sarcasm']:.2f}")
        print(f"----------------------------------------")
