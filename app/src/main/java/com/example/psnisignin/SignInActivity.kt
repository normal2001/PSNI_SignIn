package com.example.psnisignin

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView

/** Screen for entering first and last name and creating a sign-in transaction. */
class SignInActivity : BaseHomeActivity() {
    private lateinit var firstNameInput: EditText
    private lateinit var lastNameInput: EditText
    private lateinit var reasonSpinner: Spinner
    private lateinit var visitingSpinner: Spinner
    private val cacheUpdated: () -> Unit = {
        runOnUiThread { if (!isFinishing && !isDestroyed && this::visitingSpinner.isInitialized) populateRecipients() }
    }
    private lateinit var messageText: TextView
    private lateinit var submitButton: Button

    /**
     * Purpose: Initialize the sign-in form and its button handlers.
     * Inputs: savedInstanceState - Android lifecycle state.
     * Optional inputs: savedInstanceState may be null.
     * Returns: Unit.
     * Error handling: Database errors during submission are handled in submitSignIn().
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requireDatabase()) return
        setContentView(R.layout.activity_sign_in)
        bindHomeButton()

        firstNameInput = findViewById(R.id.firstNameInput)
        lastNameInput = findViewById(R.id.lastNameInput)
        visitingSpinner = findViewById(R.id.visitingSpinner)
        populateRecipients()
        reasonSpinner = findViewById(R.id.reasonSpinner)
        reasonSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, listOf("[Select option from list]") + AppConfig.REASON_OPTIONS).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        messageText = findViewById(R.id.messageText)
        submitButton = findViewById(R.id.submitSignInButton)
        submitButton.setOnClickListener { submitSignIn() }
    }

    /** Observe cache refreshes only while this form is visible. */
    override fun onResume() {
        super.onResume()
        if (!this::visitingSpinner.isInitialized) return
        SlackIntegration.cacheListeners.add(cacheUpdated)
        populateRecipients()
    }

    override fun onPause() {
        SlackIntegration.cacheListeners.remove(cacheUpdated)
        super.onPause()
    }

    private fun populateRecipients() {
        val selectedId = (visitingSpinner.selectedItem as? SlackRecipient)?.userId
        val cached = try {
            if (AppConfig.SLACK_NOTIFICATIONS_ENABLED && AppConfig.SLACK_USER_GROUP.isNotBlank())
                app.database.cachedSlackUsers(SlackIntegration.cacheScope()) else emptyList()
        } catch (e: Exception) {
            app.logger.error("SLACK_CACHE_READ_FAILED", "Could not read cached recipients", e)
            emptyList()
        }
        val recipients = cached + SlackRecipient(null, "Other")
        visitingSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, recipients).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val selection = recipients.indexOfFirst { it.userId == selectedId }
        visitingSpinner.setSelection(if (selection >= 0) selection else recipients.lastIndex)
    }

    /** Validate the form and save locally before queuing best-effort exports and notifications. */
    private fun submitSignIn() {
        val firstName = firstNameInput.text.toString().trim().replaceFirstChar { it.titlecase() }
        val lastName = lastNameInput.text.toString().trim().replaceFirstChar { it.titlecase() }
        val reason = reasonSpinner.selectedItem.toString()

        if (firstName.isEmpty() || lastName.isEmpty()) {
            showMessage("First Name and Last Name are required.")
            return
        }
        if (reasonSpinner.selectedItemPosition == 0) {
            showMessage("Reason for visit is required.")
            return
        }

        try {
            if (app.database.isAlreadySignedIn(firstName, lastName, TimeUtils.today())) {
                showMessage("Already signed in today")
                returnHomeAfterDelay()
                return
            }

            val signInDateTime = TimeUtils.nowTimestamp()
            val visitReason = reason.ifBlank { null }
            val recipient = visitingSpinner.selectedItem as SlackRecipient
            app.database.insertSignIn(firstName, lastName, signInDateTime, visitReason, recipient.displayName)
            app.csvExporter.exportCurrentYearAsync()
            SlackIntegration.notifySuccessfulSignInAsync(
                firstName,
                lastName,
                signInDateTime,
                visitReason,
                recipient,
                app.logger
            )
            submitButton.isEnabled = false
            showMessage("Successfully signed in.")
            returnHomeAfterDelay()
        } catch (e: Exception) {
            app.logger.error("SIGN_IN_UI_FAILED", "Sign-in could not be completed", e)
            showMessage("Sign in failed. Please try again or contact an administrator.")
        }
    }

    /**
     * Purpose: Show a red status/error message on the form.
     * Inputs: message - text to display.
     * Optional inputs: None.
     * Returns: Unit.
     * Error handling: No expected errors.
     */
    private fun showMessage(message: String) {
        messageText.text = message
        messageText.visibility = View.VISIBLE
    }

    /**
     * Purpose: Return to the main page four seconds after a terminal sign-in result.
     * Inputs: None.
     * Optional inputs: None.
     * Returns: Unit.
     * Error handling: Callback checks that the Activity is not already finishing/destroyed before navigating.
     */
    private fun returnHomeAfterDelay() {
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing && !isDestroyed) goHome()
        }, 4000L)
    }
}
