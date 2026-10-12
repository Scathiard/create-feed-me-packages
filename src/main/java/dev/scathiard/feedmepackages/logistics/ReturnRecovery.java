package dev.scathiard.feedmepackages.logistics;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.storage.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import java.util.*;
import static net.minecraft.commands.Commands.*;

/** Human-confirmed recovery, never a transport-loss detector. No action creates replacement cargo. */
public final class ReturnRecovery {
    private ReturnRecovery() {}
    public enum Result {
        SENT(true), REFUNDED(true), REFUND_PENDING(true), FORBIDDEN(false), STORAGE_LOCKED(false),
        MISSING(false), STALE(false), ALREADY_REJECTED(false), NOT_REJECTED(false), EVIDENCE_REQUIRED(false), BUSY(false);
        final boolean success;
        Result(boolean success) { this.success=success; }
    }
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(literal("fmp").requires(source->source.hasPermission(2))
                .then(literal("recovery").executes(context->help(context.getSource()))
                        .then(literal("list").executes(context->list(context.getSource(),0))
                                .then(argument("start",IntegerArgumentType.integer(0)).executes(context->list(context.getSource(),IntegerArgumentType.getInteger(context,"start")))))
                        .then(literal("inspect").then(argument("cache",UuidArgument.uuid()).executes(context->inspect(context.getSource(),UuidArgument.getUuid(context,"cache")))))
                        .then(confirmation("confirm-sent",true)).then(confirmation("confirm-not-sent",false))
                        .then(literal("retry-refund").then(argument("cache",UuidArgument.uuid())
                                .then(argument("handoff",UuidArgument.uuid()).executes(context->reply(context.getSource(),retryRefund(context.getSource(),
                                        UuidArgument.getUuid(context,"cache"),UuidArgument.getUuid(context,"handoff")))))))));
    }
    private static LiteralArgumentBuilder<CommandSourceStack> confirmation(String name,boolean sent) {
        return literal(name).then(argument("cache",UuidArgument.uuid()).then(argument("handoff",UuidArgument.uuid())
                .then(argument("evidence",StringArgumentType.greedyString()).executes(context->reply(context.getSource(),confirm(context.getSource(),
                        UuidArgument.getUuid(context,"cache"),UuidArgument.getUuid(context,"handoff"),sent,StringArgumentType.getString(context,"evidence")))))));
    }
    /** Both the Brigadier entry and this service enforce authority. IDs prevent applying stale confirmations to a newer hold. */
    public static Result confirm(CommandSourceStack source,UUID cache,UUID handoff,boolean sent,String evidence) {
        if (!allowed(source)) return Result.FORBIDDEN;
        if (!validEvidence(evidence)) return Result.EVIDENCE_REQUIRED;
        if (ReturnService.operating(source.getServer(),cache)) return Result.BUSY;
        var ledger=CacheLedger.get(source.getServer());
        if (!ledger.problem().isEmpty()) return Result.STORAGE_LOCKED;
        var hold=ledger.returnEscrow(cache);
        if (hold==null) return Result.MISSING;
        if (!hold.id().equals(handoff)) return Result.STALE;
        if (hold.rejected()) return Result.ALREADY_REJECTED;
        // Validate every original source before changing its disposition; a corrupt hold never unlocks as empty stock.
        hold.settled(ledger.find(cache),source.registryAccess());
        String decision=sent?"sent":"not-sent";
        audit(source,hold,decision,"begin",evidence.strip());
        Result result;
        if (sent) {
            ledger.finishReturn(hold,source.registryAccess());ReturnService.recovered(hold,true);result=Result.SENT;
        } else {
            var rejected=ledger.rejectReturn(hold);
            result=ReturnService.retryRefund(source.getServer(),rejected)?Result.REFUNDED:Result.REFUND_PENDING;
            ReturnService.recovered(hold,false);
        }
        audit(source,hold,decision,result.name(),evidence.strip());return result;
    }
    public static Result retryRefund(CommandSourceStack source,UUID cache,UUID handoff) {
        if (!allowed(source)) return Result.FORBIDDEN;
        if (ReturnService.operating(source.getServer(),cache)) return Result.BUSY;
        var ledger=CacheLedger.get(source.getServer());if (!ledger.problem().isEmpty()) return Result.STORAGE_LOCKED;
        var hold=ledger.returnEscrow(cache);
        if (hold==null) return Result.MISSING;
        if (!hold.id().equals(handoff)) return Result.STALE;
        if (!hold.rejected()) return Result.NOT_REJECTED;
        hold.settled(ledger.find(cache),source.registryAccess());
        audit(source,hold,"retry-refund","begin","previous-confirmed-rejection");
        var result=ReturnService.retryRefund(source.getServer(),hold)?Result.REFUNDED:Result.REFUND_PENDING;
        if (result==Result.REFUNDED) ReturnService.recovered(hold,false);
        audit(source,hold,"retry-refund",result.name(),"previous-confirmed-rejection");return result;
    }
    private static boolean allowed(CommandSourceStack source) { return source.hasPermission(2) && source.getServer().isSameThread(); }
    private static boolean validEvidence(String value) {
        return value!=null && !value.isBlank() && value.length()<=256 && value.codePoints().noneMatch(c->Character.isISOControl(c)
                || Character.getType(c)==Character.LINE_SEPARATOR || Character.getType(c)==Character.PARAGRAPH_SEPARATOR || Character.getType(c)==Character.FORMAT);
    }
    private static int help(CommandSourceStack source) {
        source.sendSuccess(()->message("help"),false);return 1;
    }
    private static int list(CommandSourceStack source,int start) {
        var ledger=CacheLedger.get(source.getServer());if(!ledger.problem().isEmpty())return reply(source,Result.STORAGE_LOCKED);
        var holds=ledger.unresolvedReturns().stream().sorted(Comparator.comparing(h->h.handle().cacheId())).toList();
        source.sendSuccess(()->message("list",holds.size(),start),false);
        for(var hold:holds.stream().skip(start).limit(20).toList())
            source.sendSuccess(()->message("row",hold.handle().cacheId(),hold.id(),hold.rejected()?message("rejected"):message("uncertain")),false);
        return 1;
    }
    private static int inspect(CommandSourceStack source,UUID cache) {
        var ledger=CacheLedger.get(source.getServer());if(!ledger.problem().isEmpty())return reply(source,Result.STORAGE_LOCKED);
        var hold=ledger.returnEscrow(cache);if(hold==null)return reply(source,Result.MISSING);
        source.sendSuccess(()->message("details",cache,hold.id(),hold.handle().playerId(),hold.transport(),hold.address(),
                cargoIdentity(hold),hold.amount(),hold.slot(),hold.inventorySlot()>=0?"inventory:"+hold.inventorySlot():"cache:"+hold.cacheSlot(),
                hold.rejected()?message("rejected"):message("uncertain")),false);
        source.sendSuccess(()->message("warning"),false);return 1;
    }
    private static Component message(String key,Object... values) {
        // UUID is printable on a headless source but is not a legal network translation argument.
        Object[] arguments=Arrays.stream(values).map(value->value instanceof UUID?value.toString():value).toArray();
        return Component.translatable("command.create_feed_me_packages.recovery."+key,arguments);
    }
    private static int reply(CommandSourceStack source,Result result) {
        Component text=message(result.name().toLowerCase(Locale.ROOT));
        if(result.success)source.sendSuccess(()->text,false);else source.sendFailure(text);
        return result.success?1:0;
    }
    private static String cargoIdentity(ReturnEscrow hold) {
        try {
            byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(hold.encodedVariant().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return (hold.fluid()?"fluid/mB ":"item ")+hold.variant()+" [sha256:"+HexFormat.of().formatHex(digest)+"]";
        } catch(java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException("Required JDK digest unavailable",unavailable); }
    }
    private static void audit(CommandSourceStack source,ReturnEscrow hold,String decision,String status,String evidence) {
        FeedMePackages.LOGGER.warn("FMP_RETURN_RECOVERY actor={} cache={} handoff={} originalPlayer={} decision={} status={} cargo={} amount={} carrier={} source={} evidence={}",
                source.getTextName(),hold.handle().cacheId(),hold.id(),hold.handle().playerId(),decision,status,cargoIdentity(hold),hold.amount(),
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(hold.carrier().getItem()),hold.inventorySlot()>=0?"inventory:"+hold.inventorySlot():"cache:"+hold.cacheSlot(),evidence);
    }
}
