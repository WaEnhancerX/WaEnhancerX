package com.waenhancer.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.waenhancer.ui.designsystem.WaexTheme

   @Composable
   fun WaexCard(
       modifier: Modifier = Modifier,
       onClick: (() -> Unit)? = null,
       content: @Composable () -> Unit
   ) {
       val colors = WaexTheme.colors
       val radius = WaexTheme.radius
       val elevation = WaexTheme.elevation

       val cardContent: @Composable () -> Unit = {
           Box(modifier = Modifier.padding(WaexTheme.spacing.stackLg)) { content() }
       }
       if (onClick != null) {
           Card(
               onClick = onClick,
               modifier = modifier,
               shape = radius.cardShape,
               colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
               elevation = CardDefaults.cardElevation(
                   defaultElevation = elevation.card,
                   pressedElevation = elevation.none,
                   hoveredElevation = elevation.button,
                   focusedElevation = elevation.button
               ),
               content = { cardContent() }
           )
       } else {
           Card(
               modifier = modifier,
               shape = radius.cardShape,
               colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
               elevation = CardDefaults.cardElevation(defaultElevation = elevation.card),
               content = { cardContent() }
           )
       }
   }
