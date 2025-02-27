/*
 * Copyright 2013-2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.whispersystems.textsecuregcm.controllers;

import static com.codahale.metrics.MetricRegistry.name;

import com.codahale.metrics.annotation.Timed;
import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.ByteString;
import io.dropwizard.auth.Auth;
import io.dropwizard.util.DataSize;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.abuse.FilterAbusiveMessages;
import org.whispersystems.textsecuregcm.auth.Anonymous;
import org.whispersystems.textsecuregcm.auth.AuthenticatedAccount;
import org.whispersystems.textsecuregcm.auth.CombinedUnidentifiedSenderAccessKeys;
import org.whispersystems.textsecuregcm.auth.OptionalAccess;
import org.whispersystems.textsecuregcm.entities.AccountMismatchedDevices;
import org.whispersystems.textsecuregcm.entities.AccountStaleDevices;
import org.whispersystems.textsecuregcm.entities.IncomingMessage;
import org.whispersystems.textsecuregcm.entities.IncomingMessageList;
import org.whispersystems.textsecuregcm.entities.MessageProtos.Envelope;
import org.whispersystems.textsecuregcm.entities.MessageProtos.Envelope.Type;
import org.whispersystems.textsecuregcm.entities.MismatchedDevices;
import org.whispersystems.textsecuregcm.entities.MultiRecipientMessage;
import org.whispersystems.textsecuregcm.entities.MultiRecipientMessage.Recipient;
import org.whispersystems.textsecuregcm.entities.OutgoingMessageEntity;
import org.whispersystems.textsecuregcm.entities.OutgoingMessageEntityList;
import org.whispersystems.textsecuregcm.entities.SendMessageResponse;
import org.whispersystems.textsecuregcm.entities.SendMultiRecipientMessageResponse;
import org.whispersystems.textsecuregcm.entities.StaleDevices;
import org.whispersystems.textsecuregcm.limits.RateLimitChallengeException;
import org.whispersystems.textsecuregcm.limits.RateLimiters;
import org.whispersystems.textsecuregcm.metrics.MessageMetrics;
import org.whispersystems.textsecuregcm.metrics.UserAgentTagUtil;
import org.whispersystems.textsecuregcm.providers.MultiRecipientMessageProvider;
import org.whispersystems.textsecuregcm.push.MessageSender;
import org.whispersystems.textsecuregcm.push.NotPushRegisteredException;
import org.whispersystems.textsecuregcm.push.PushNotificationManager;
import org.whispersystems.textsecuregcm.push.ReceiptSender;
import org.whispersystems.textsecuregcm.storage.Account;
import org.whispersystems.textsecuregcm.storage.AccountsManager;
import org.whispersystems.textsecuregcm.storage.DeletedAccountsManager;
import org.whispersystems.textsecuregcm.storage.Device;
import org.whispersystems.textsecuregcm.storage.MessagesManager;
import org.whispersystems.textsecuregcm.storage.ReportMessageManager;
import org.whispersystems.textsecuregcm.util.DestinationDeviceValidator;
import org.whispersystems.textsecuregcm.util.Pair;
import org.whispersystems.textsecuregcm.util.Util;
import org.whispersystems.textsecuregcm.util.ua.UnrecognizedUserAgentException;
import org.whispersystems.textsecuregcm.util.ua.UserAgentUtil;
import org.whispersystems.textsecuregcm.websocket.WebSocketConnection;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
@Path("/v1/messages")
public class MessageController {

  private static final Logger logger = LoggerFactory.getLogger(MessageController.class);

  private final RateLimiters rateLimiters;
  private final MessageSender messageSender;
  private final ReceiptSender receiptSender;
  private final AccountsManager accountsManager;
  private final DeletedAccountsManager deletedAccountsManager;
  private final MessagesManager messagesManager;
  private final PushNotificationManager pushNotificationManager;
  private final ReportMessageManager reportMessageManager;
  private final ExecutorService multiRecipientMessageExecutor;

  private static final String REJECT_OVERSIZE_MESSAGE_COUNTER = name(MessageController.class, "rejectOversizeMessage");
  private static final String SENT_MESSAGE_COUNTER_NAME = name(MessageController.class, "sentMessages");
  private static final String CONTENT_SIZE_DISTRIBUTION_NAME = name(MessageController.class, "messageContentSize");
  private static final String OUTGOING_MESSAGE_LIST_SIZE_BYTES_DISTRIBUTION_NAME = name(MessageController.class, "outgoingMessageListSizeBytes");
  private static final String RATE_LIMITED_MESSAGE_COUNTER_NAME = name(MessageController.class, "rateLimitedMessage");
  private static final String REJECT_INVALID_ENVELOPE_TYPE = name(MessageController.class, "rejectInvalidEnvelopeType");

  private static final String EPHEMERAL_TAG_NAME = "ephemeral";
  private static final String SENDER_TYPE_TAG_NAME = "senderType";
  private static final String SENDER_COUNTRY_TAG_NAME = "senderCountry";
  private static final String RATE_LIMIT_REASON_TAG_NAME = "rateLimitReason";
  private static final String ENVELOPE_TYPE_TAG_NAME = "envelopeType";

  private static final String SENDER_TYPE_IDENTIFIED = "identified";
  private static final String SENDER_TYPE_UNIDENTIFIED = "unidentified";
  private static final String SENDER_TYPE_SELF = "self";

  @VisibleForTesting
  static final long MAX_MESSAGE_SIZE = DataSize.kibibytes(256).toBytes();

  public MessageController(
      RateLimiters rateLimiters,
      MessageSender messageSender,
      ReceiptSender receiptSender,
      AccountsManager accountsManager,
      DeletedAccountsManager deletedAccountsManager,
      MessagesManager messagesManager,
      PushNotificationManager pushNotificationManager,
      ReportMessageManager reportMessageManager,
      @Nonnull ExecutorService multiRecipientMessageExecutor) {
    this.rateLimiters = rateLimiters;
    this.messageSender = messageSender;
    this.receiptSender = receiptSender;
    this.accountsManager = accountsManager;
    this.deletedAccountsManager = deletedAccountsManager;
    this.messagesManager = messagesManager;
    this.pushNotificationManager = pushNotificationManager;
    this.reportMessageManager = reportMessageManager;
    this.multiRecipientMessageExecutor = Objects.requireNonNull(multiRecipientMessageExecutor);
  }

  @Timed
  @Path("/{destination}")
  @PUT
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  @FilterAbusiveMessages
  public Response sendMessage(@Auth Optional<AuthenticatedAccount> source,
      @HeaderParam(OptionalAccess.UNIDENTIFIED) Optional<Anonymous> accessKey,
      @HeaderParam("User-Agent") String userAgent,
      @HeaderParam("X-Forwarded-For") String forwardedFor,
      @PathParam("destination") UUID destinationUuid,
      @NotNull @Valid IncomingMessageList messages)
      throws RateLimitExceededException, RateLimitChallengeException {

    if (source.isEmpty() && accessKey.isEmpty()) {
      throw new WebApplicationException(Response.Status.UNAUTHORIZED);
    }

    final String senderType;

    if (source.isPresent()) {
      if (source.get().getAccount().isIdentifiedBy(destinationUuid)) {
        senderType = SENDER_TYPE_SELF;
      } else {
        senderType = SENDER_TYPE_IDENTIFIED;
      }
    } else {
      senderType = SENDER_TYPE_UNIDENTIFIED;
    }

    for (final IncomingMessage message : messages.messages()) {

      int contentLength = 0;

      if (!Util.isEmpty(message.content())) {
        contentLength += message.content().length();
      }

      validateContentLength(contentLength, userAgent);
      validateEnvelopeType(message.type(), userAgent);
    }

    try {
      boolean isSyncMessage = source.isPresent() && source.get().getAccount().isIdentifiedBy(destinationUuid);

      Optional<Account> destination;

      if (!isSyncMessage) {
        destination = accountsManager.getByAccountIdentifier(destinationUuid)
            .or(() -> accountsManager.getByPhoneNumberIdentifier(destinationUuid));
      } else {
        destination = source.map(AuthenticatedAccount::getAccount);
      }

      OptionalAccess.verify(source.map(AuthenticatedAccount::getAccount), accessKey, destination);
      assert (destination.isPresent());

      if (source.isPresent() && !isSyncMessage) {
        checkRateLimit(source.get(), destination.get(), userAgent);
      }

      final Set<Long> excludedDeviceIds;

      if (isSyncMessage) {
        excludedDeviceIds = Set.of(source.get().getAuthenticatedDevice().getId());
      } else {
        excludedDeviceIds = Collections.emptySet();
      }

      DestinationDeviceValidator.validateCompleteDeviceList(destination.get(),
          messages.messages().stream().map(IncomingMessage::destinationDeviceId).collect(Collectors.toSet()),
          excludedDeviceIds);

      DestinationDeviceValidator.validateRegistrationIds(destination.get(),
          messages.messages(),
          IncomingMessage::destinationDeviceId,
          IncomingMessage::destinationRegistrationId,
          destination.get().getPhoneNumberIdentifier().equals(destinationUuid));

      final List<Tag> tags = List.of(UserAgentTagUtil.getPlatformTag(userAgent),
          Tag.of(EPHEMERAL_TAG_NAME, String.valueOf(messages.online())),
          Tag.of(SENDER_TYPE_TAG_NAME, senderType));

      for (IncomingMessage incomingMessage : messages.messages()) {
        Optional<Device> destinationDevice = destination.get().getDevice(incomingMessage.destinationDeviceId());

        if (destinationDevice.isPresent()) {
          Metrics.counter(SENT_MESSAGE_COUNTER_NAME, tags).increment();
          sendMessage(source, destination.get(), destinationDevice.get(), destinationUuid, messages.timestamp(), messages.online(), messages.urgent(), incomingMessage, userAgent);
        }
      }

      return Response.ok(new SendMessageResponse(
          !isSyncMessage && source.isPresent() && source.get().getAccount().getEnabledDeviceCount() > 1)).build();
    } catch (NoSuchUserException e) {
      throw new WebApplicationException(Response.status(404).build());
    } catch (MismatchedDevicesException e) {
      throw new WebApplicationException(Response.status(409)
              .type(MediaType.APPLICATION_JSON_TYPE)
              .entity(new MismatchedDevices(e.getMissingDevices(),
                      e.getExtraDevices()))
              .build());
    } catch (StaleDevicesException e) {
      throw new WebApplicationException(Response.status(410)
              .type(MediaType.APPLICATION_JSON)
              .entity(new StaleDevices(e.getStaleDevices()))
              .build());
    }
  }

  @Timed
  @Path("/multi_recipient")
  @PUT
  @Consumes(MultiRecipientMessageProvider.MEDIA_TYPE)
  @Produces(MediaType.APPLICATION_JSON)
  @FilterAbusiveMessages
  public Response sendMultiRecipientMessage(
      @HeaderParam(OptionalAccess.UNIDENTIFIED) CombinedUnidentifiedSenderAccessKeys accessKeys,
      @HeaderParam("User-Agent") String userAgent,
      @HeaderParam("X-Forwarded-For") String forwardedFor,
      @QueryParam("online") boolean online,
      @QueryParam("ts") long timestamp,
      @NotNull @Valid MultiRecipientMessage multiRecipientMessage) {

    Map<UUID, Account> uuidToAccountMap = Arrays.stream(multiRecipientMessage.getRecipients())
        .map(Recipient::getUuid)
        .distinct()
        .collect(Collectors.toUnmodifiableMap(Function.identity(), uuid -> {
          Optional<Account> account = accountsManager.getByAccountIdentifier(uuid);
          if (account.isEmpty()) {
            throw new WebApplicationException(Status.NOT_FOUND);
          }
          return account.get();
        }));
    checkAccessKeys(accessKeys, uuidToAccountMap);

    final Map<Account, HashSet<Pair<Long, Integer>>> accountToDeviceIdAndRegistrationIdMap =
        Arrays
            .stream(multiRecipientMessage.getRecipients())
            .collect(Collectors.toMap(
                recipient -> uuidToAccountMap.get(recipient.getUuid()),
                recipient -> new HashSet<>(
                    Collections.singletonList(new Pair<>(recipient.getDeviceId(), recipient.getRegistrationId()))),
                (a, b) -> {
                  a.addAll(b);
                  return a;
                }
            ));

    Collection<AccountMismatchedDevices> accountMismatchedDevices = new ArrayList<>();
    Collection<AccountStaleDevices> accountStaleDevices = new ArrayList<>();
    uuidToAccountMap.values().forEach(account -> {
      final Set<Long> deviceIds = accountToDeviceIdAndRegistrationIdMap.get(account).stream().map(Pair::first)
          .collect(Collectors.toSet());
      try {
        DestinationDeviceValidator.validateCompleteDeviceList(account, deviceIds, Collections.emptySet());

        // Multi-recipient messages are always sealed-sender messages, and so can never be sent to a phone number
        // identity
        DestinationDeviceValidator.validateRegistrationIds(
            account,
            accountToDeviceIdAndRegistrationIdMap.get(account).stream(),
            false);
      } catch (MismatchedDevicesException e) {
        accountMismatchedDevices.add(new AccountMismatchedDevices(account.getUuid(),
            new MismatchedDevices(e.getMissingDevices(), e.getExtraDevices())));
      } catch (StaleDevicesException e) {
        accountStaleDevices.add(new AccountStaleDevices(account.getUuid(), new StaleDevices(e.getStaleDevices())));
      }
    });
    if (!accountMismatchedDevices.isEmpty()) {
      return Response
          .status(409)
          .type(MediaType.APPLICATION_JSON_TYPE)
          .entity(accountMismatchedDevices)
          .build();
    }
    if (!accountStaleDevices.isEmpty()) {
      return Response
          .status(410)
          .type(MediaType.APPLICATION_JSON)
          .entity(accountStaleDevices)
          .build();
    }

    List<UUID> uuids404 = Collections.synchronizedList(new ArrayList<>());

    try {
      final Counter sentMessageCounter = Metrics.counter(SENT_MESSAGE_COUNTER_NAME, Tags.of(
          UserAgentTagUtil.getPlatformTag(userAgent),
          Tag.of(EPHEMERAL_TAG_NAME, String.valueOf(online)),
          Tag.of(SENDER_TYPE_TAG_NAME, SENDER_TYPE_UNIDENTIFIED)));

      multiRecipientMessageExecutor.invokeAll(Arrays.stream(multiRecipientMessage.getRecipients())
          .map(recipient -> (Callable<Void>) () -> {
            Account destinationAccount = uuidToAccountMap.get(recipient.getUuid());

            // we asserted this must exist in validateCompleteDeviceList
            Device destinationDevice = destinationAccount.getDevice(recipient.getDeviceId()).orElseThrow();
            sentMessageCounter.increment();
            try {
              sendMessage(destinationAccount, destinationDevice, timestamp, online, recipient,
                  multiRecipientMessage.getCommonPayload());
            } catch (NoSuchUserException e) {
              uuids404.add(destinationAccount.getUuid());
            }
            return null;
          })
          .collect(Collectors.toList()));
    } catch (InterruptedException e) {
      logger.error("interrupted while delivering multi-recipient messages", e);
      return Response.serverError().entity("interrupted during delivery").build();
    }
    return Response.ok(new SendMultiRecipientMessageResponse(uuids404)).build();
  }

  private void checkAccessKeys(CombinedUnidentifiedSenderAccessKeys accessKeys, Map<UUID, Account> uuidToAccountMap) {
    AtomicBoolean throwUnauthorized = new AtomicBoolean(false);
    byte[] empty = new byte[16];
    final Optional<byte[]> UNRESTRICTED_UNIDENTIFIED_ACCESS_KEY = Optional.of(new byte[16]);
    byte[] combinedUnknownAccessKeys = uuidToAccountMap.values().stream()
        .map(account -> {
          if (account.isUnrestrictedUnidentifiedAccess()) {
            return UNRESTRICTED_UNIDENTIFIED_ACCESS_KEY;
          } else {
            return account.getUnidentifiedAccessKey();
          }
        })
        .map(accessKey -> {
          if (accessKey.isEmpty()) {
            throwUnauthorized.set(true);
            return empty;
          }
          return accessKey.get();
        })
        .reduce(new byte[16], (bytes, bytes2) -> {
          if (bytes.length != bytes2.length) {
            throwUnauthorized.set(true);
            return bytes;
          }
          for (int i = 0; i < bytes.length; i++) {
            bytes[i] ^= bytes2[i];
          }
          return bytes;
        });
    if (throwUnauthorized.get()
        || !MessageDigest.isEqual(combinedUnknownAccessKeys, accessKeys.getAccessKeys())) {
      throw new WebApplicationException(Status.UNAUTHORIZED);
    }
  }

  @Timed
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public OutgoingMessageEntityList getPendingMessages(@Auth AuthenticatedAccount auth,
      @HeaderParam("User-Agent") String userAgent) {

    pushNotificationManager.handleMessagesRetrieved(auth.getAccount(), auth.getAuthenticatedDevice(), userAgent);

    final OutgoingMessageEntityList outgoingMessages;
    {
      final Pair<List<Envelope>, Boolean> messagesAndHasMore = messagesManager.getMessagesForDevice(
          auth.getAccount().getUuid(),
          auth.getAuthenticatedDevice().getId(),
          false);

      outgoingMessages = new OutgoingMessageEntityList(messagesAndHasMore.first().stream()
          .map(OutgoingMessageEntity::fromEnvelope)
          .peek(outgoingMessageEntity -> MessageMetrics.measureAccountOutgoingMessageUuidMismatches(auth.getAccount(),
              outgoingMessageEntity))
          .collect(Collectors.toList()),
          messagesAndHasMore.second());
    }

    {
      String platform;

      try {
        platform = UserAgentUtil.parseUserAgentString(userAgent).getPlatform().name().toLowerCase();
      } catch (final UnrecognizedUserAgentException ignored) {
        platform = "unrecognized";
      }

      Metrics.summary(OUTGOING_MESSAGE_LIST_SIZE_BYTES_DISTRIBUTION_NAME, "platform", platform).record(estimateMessageListSizeBytes(outgoingMessages));
    }

    return outgoingMessages;
  }

  private static long estimateMessageListSizeBytes(final OutgoingMessageEntityList messageList) {
    long size = 0;

    for (final OutgoingMessageEntity message : messageList.messages()) {
      size += message.content() == null ? 0 : message.content().length;
      size += message.sourceUuid() == null ? 0 : 36;
    }

    return size;
  }

  @Timed
  @DELETE
  @Path("/uuid/{uuid}")
  public void removePendingMessage(@Auth AuthenticatedAccount auth, @PathParam("uuid") UUID uuid) {
    messagesManager.delete(
        auth.getAccount().getUuid(),
        auth.getAuthenticatedDevice().getId(),
        uuid,
        null).ifPresent(deletedMessage -> {

      WebSocketConnection.recordMessageDeliveryDuration(deletedMessage.getTimestamp(), auth.getAuthenticatedDevice());

      if (deletedMessage.hasSourceUuid() && deletedMessage.getType() != Type.SERVER_DELIVERY_RECEIPT) {
        try {
          receiptSender.sendReceipt(
              UUID.fromString(deletedMessage.getDestinationUuid()), auth.getAuthenticatedDevice().getId(),
              UUID.fromString(deletedMessage.getSourceUuid()), deletedMessage.getTimestamp());
        } catch (Exception e) {
          logger.warn("Failed to send delivery receipt", e);
        }
      }
    });
  }

  @Timed
  @POST
  @Path("/report/{source}/{messageGuid}")
  public Response reportMessage(@Auth AuthenticatedAccount auth, @PathParam("source") String source,
      @PathParam("messageGuid") UUID messageGuid) {

    final Optional<String> sourceNumber;
    final Optional<UUID> sourceAci;
    final Optional<UUID> sourcePni;
    if (source.startsWith("+")) {
      sourceNumber = Optional.of(source);
      final Optional<Account> maybeAccount = accountsManager.getByE164(source);
      if (maybeAccount.isPresent()) {
        sourceAci = maybeAccount.map(Account::getUuid);
        sourcePni = maybeAccount.map(Account::getPhoneNumberIdentifier);
      } else {
        sourceAci = deletedAccountsManager.findDeletedAccountAci(source);
        sourcePni = Optional.ofNullable(accountsManager.getPhoneNumberIdentifier(source));
      }
    } else {
      sourceAci = Optional.of(UUID.fromString(source));

      final Optional<Account> sourceAccount = accountsManager.getByAccountIdentifier(sourceAci.get());

      if (sourceAccount.isEmpty()) {
        logger.warn("Could not find source: {}", sourceAci.get());
        sourceNumber = deletedAccountsManager.findDeletedAccountE164(sourceAci.get());
        sourcePni = sourceNumber.map(accountsManager::getPhoneNumberIdentifier);
      } else {
        sourceNumber = sourceAccount.map(Account::getNumber);
        sourcePni = sourceAccount.map(Account::getPhoneNumberIdentifier);
      }
    }

    reportMessageManager.report(sourceNumber, sourceAci, sourcePni, messageGuid, auth.getAccount().getUuid());

    return Response.status(Status.ACCEPTED)
        .build();
  }

  private void sendMessage(Optional<AuthenticatedAccount> source,
      Account destinationAccount,
      Device destinationDevice,
      UUID destinationUuid,
      long timestamp,
      boolean online,
      boolean urgent,
      IncomingMessage incomingMessage,
      String userAgentString)
      throws NoSuchUserException {
    try {
      final Envelope envelope;

      try {
        envelope = incomingMessage.toEnvelope(destinationUuid,
            source.map(AuthenticatedAccount::getAccount).orElse(null),
            source.map(authenticatedAccount -> authenticatedAccount.getAuthenticatedDevice().getId()).orElse(null),
            timestamp == 0 ? System.currentTimeMillis() : timestamp,
            urgent);
      } catch (final IllegalArgumentException e) {
        logger.warn("Received bad envelope type {} from {}", incomingMessage.type(), userAgentString);
        throw new BadRequestException(e);
      }

      messageSender.sendMessage(destinationAccount, destinationDevice, envelope, online);
    } catch (NotPushRegisteredException e) {
      if (destinationDevice.isMaster()) throw new NoSuchUserException(e);
      else                              logger.debug("Not registered", e);
    }
  }

  private void sendMessage(Account destinationAccount,
      Device destinationDevice,
      long timestamp,
      boolean online,
      Recipient recipient,
      byte[] commonPayload) throws NoSuchUserException {
    try {
      Envelope.Builder messageBuilder = Envelope.newBuilder();
      long serverTimestamp = System.currentTimeMillis();
      byte[] recipientKeyMaterial = recipient.getPerRecipientKeyMaterial();

      byte[] payload = new byte[1 + recipientKeyMaterial.length + commonPayload.length];
      payload[0] = MultiRecipientMessageProvider.VERSION;
      System.arraycopy(recipientKeyMaterial, 0, payload, 1, recipientKeyMaterial.length);
      System.arraycopy(commonPayload, 0, payload, 1 + recipientKeyMaterial.length, commonPayload.length);

      messageBuilder
          .setType(Type.UNIDENTIFIED_SENDER)
          .setTimestamp(timestamp == 0 ? serverTimestamp : timestamp)
          .setServerTimestamp(serverTimestamp)
          .setContent(ByteString.copyFrom(payload))
          .setDestinationUuid(destinationAccount.getUuid().toString());

      messageSender.sendMessage(destinationAccount, destinationDevice, messageBuilder.build(), online);
    } catch (NotPushRegisteredException e) {
      if (destinationDevice.isMaster()) {
        throw new NoSuchUserException(e);
      } else {
        logger.debug("Not registered", e);
      }
    }
  }

  private void checkRateLimit(AuthenticatedAccount source, Account destination, String userAgent)
      throws RateLimitExceededException {
    final String senderCountryCode = Util.getCountryCode(source.getAccount().getNumber());

    try {
      rateLimiters.getMessagesLimiter().validate(source.getAccount().getUuid(), destination.getUuid());
    } catch (final RateLimitExceededException e) {
      Metrics.counter(RATE_LIMITED_MESSAGE_COUNTER_NAME,
          Tags.of(
              UserAgentTagUtil.getPlatformTag(userAgent),
              Tag.of(SENDER_COUNTRY_TAG_NAME, senderCountryCode),
              Tag.of(RATE_LIMIT_REASON_TAG_NAME, "singleDestinationRate"))).increment();

      throw e;
    }
  }

  private void validateContentLength(final int contentLength, final String userAgent) {
    Metrics.summary(CONTENT_SIZE_DISTRIBUTION_NAME, Tags.of(UserAgentTagUtil.getPlatformTag(userAgent)))
        .record(contentLength);

    if (contentLength > MAX_MESSAGE_SIZE) {
      Metrics.counter(REJECT_OVERSIZE_MESSAGE_COUNTER, Tags.of(UserAgentTagUtil.getPlatformTag(userAgent)))
          .increment();
      throw new WebApplicationException(Status.REQUEST_ENTITY_TOO_LARGE);
    }

  }

  private void validateEnvelopeType(final int type, final String userAgent) {
    if (type == Type.SERVER_DELIVERY_RECEIPT_VALUE) {
      Metrics.counter(REJECT_INVALID_ENVELOPE_TYPE,
              Tags.of(UserAgentTagUtil.getPlatformTag(userAgent), Tag.of(ENVELOPE_TYPE_TAG_NAME, String.valueOf(type))))
          .increment();
      throw new BadRequestException("reserved envelope type");
    }
  }

  public static Optional<byte[]> getMessageContent(IncomingMessage message) {
    if (Util.isEmpty(message.content())) return Optional.empty();

    try {
      return Optional.of(Base64.getDecoder().decode(message.content()));
    } catch (IllegalArgumentException e) {
      logger.debug("Bad B64", e);
      return Optional.empty();
    }
  }
}
