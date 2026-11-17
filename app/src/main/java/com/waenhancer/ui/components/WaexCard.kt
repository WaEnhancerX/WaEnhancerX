package com.waenhancer.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.glass.LocalWaexLiquidGlass
import com.waenhancer.ui.glass.waexGlass

   @Composable
   fun WaexCard(
       modifier: Modifier = Modifier,
       onClick: (() -> Unit)? = null,
       content: @Composable () -> Unit
   ) {
       val colors = WaexTheme.colors
       val radius = WaexTheme.radius
       val elevation = WaexTheme.elevation
       val glass = LocalWaexLiquidGlass.current
       // Liquid glass: translucent tint + specular rim, no drop shadow.
       val glassModifier = if (glass) {
           modifier.waexGlass(shape = radius.cardShape, tint = colors.surfaceContainer, rimAlpha = 0.45f)
       } else modifier
       val containerColor = if (glass) Color.Transparent else colors.surfaceContainer

       val cardContent: @Composable () -> Unit = {
           Box(modifier = Modifier.padding(WaexTheme.spacing.stackLg)) { content() }
       }
       if (onClick != null) {
           Card(
               onClick = onClick,
               modifier = glassModifier,
               shape = radius.cardShape,
               colors = CardDefaults.cardColors(containerColor = containerColor),
               elevation = CardDefaults.cardElevation(
                   defaultElevation = if (glass) elevation.none else elevation.card,
                   pressedElevation = elevation.none,
                   hoveredElevation = if (glass) elevation.none else elevation.button,
                   focusedElevation = if (glass) elevation.none else elevation.button
               ),
               content = { cardContent() }
           )
       } else {
           Card(
               modifier = glassModifier,
               shape = radius.cardShape,
               colors = CardDefaults.cardColors(containerColor = containerColor),
               elevation = CardDefaults.cardElevation(defaultElevation = if (glass) elevation.none else elevation.card),
               content = { cardContent() }
           )
       }
   }